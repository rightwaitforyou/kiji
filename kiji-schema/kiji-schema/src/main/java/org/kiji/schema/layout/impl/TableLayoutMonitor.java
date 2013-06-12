/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.layout.impl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Preconditions;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.KijiURI;
import org.kiji.schema.util.Lock;
import org.kiji.schema.util.ZooKeeperLock;

/**
 * Monitor tracking table layouts.
 *
 * <p>
 *   The monitor roles include:
 *   <ul>
 *     <li> Reporting new layout updates to active users of a table:
 *       When a table's layout is being updated, users of that table will receive a notification and
 *       will automatically reload the table layout.
 *     </li>
 *     <li> Reporting active users of a table to table management client processes:
 *       Every user of a table will advertise itself as such and report the version of the table
 *       layout it currently uses.
 *       This makes it possible for a process to ensure that table users have a consistent view
 *       on the table layout before applying further updates.
 *     </li>
 *   </ul>
 * </p>
 *
 * <h2> ZooKeeper node tree structure </h2>
 *
 * <p>
 *  The monitor manages a tree of ZooKeeper nodes organized as follows:
 *  <ul>
 *    <li> {@code /kiji-schema} : Root ZooKeeper node for all Kiji instances. </li>
 *    <li> {@code /kiji-schema/instances/[instance-name]} :
 *        Root ZooKeeper node for the Kiji instance with name "instance-name".
 *    </li>
 *    <li> {@code /kiji-schema/instances/[instance-name]/tables/[table-name]} :
 *        Root ZooKeeper node for the Kiji table with name "table-name" belonging to the Kiji
 *        instance named "instance-name".
 *    </li>
 *  </ul>
 * </p>
 *
 * <h2> ZooKeeper nodes for a Kiji table </h2>
 *
 * Every table is associated with three ZooKeeper nodes:
 * <ul>
 *   <li>
 *     {@code /kiji-schema/instances/[instance-name]/tables/[table-name]/layout} :
 *     this node contains the most recent version of the table layout.
 *     Clients should watch this node for changes to be notified of table layout updates.
 *   </li>
 *   <li>
 *     {@code /kiji-schema/instances/[instance-name]/tables/[table-name]/users} :
 *     this directory contains a node for each user of the table;
 *     each user's node contains the version of the layout as seen by the client.
 *     Management tools should watch these users' nodes to ensure that all clients have a
 *     consistent view on a table's layout before/after pushing new updates.
 *   </li>
 *   <li>
 *     {@code /kiji-schema/instances/[instance-name]/tables/[table-name]/layout_update_lock} :
 *     this directory node is used to acquire exclusive lock for table layout updates.
 *     Layout management tools are required to acquire this lock before proceeding with any
 *     table layout update.
 *   </li>
 * </ul>
 */
@ApiAudience.Private
public final class TableLayoutMonitor implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(TableLayoutMonitor.class);

  /** Root path of the ZooKeeper directory node where to write Kiji nodes. */
  private static final File ROOT_ZOOKEEPER_PATH = new File("/kiji-schema");

  // -----------------------------------------------------------------------------------------------

  /**
   * Reports the ZooKeeper node path for a Kiji table.
   *
   * @param tableURI URI of a Kiji table to report the ZooKeeper node path for.
   * @return the ZooKeeper node path for a Kiji table.
   */
  public static File getTableDir(KijiURI tableURI) {
    return new File(String.format("%s/instances/%s/tables/%s",
        ROOT_ZOOKEEPER_PATH, tableURI.getInstance(), tableURI.getTable()));
  }

  /**
   * Reports the path of the ZooKeeper node containing the most recent version of a table's layout.
   *
   * @param tableURI Reports the path of the ZooKeeper node that contains the most recent layout
   *     version of the Kiji table identified by this URI.
   * @return the path of the ZooKeeper node that contains the most recent layout version of the
   *     specified Kiji table.
   */
  public static File getTableLayoutFile(KijiURI tableURI) {
    return new File(getTableDir(tableURI), "layout");
  }

  /**
   * Reports the path of the ZooKeeper node where users of a table register themselves.
   *
   * @param tableURI Reports the path of the ZooKeeper node where users of the Kiji table with this
   *     URI register themselves.
   * @return the path of the ZooKeeper node where users of a table register.
   */
  public static File getTableUsersDir(KijiURI tableURI) {
    return new File(getTableDir(tableURI), "users");
  }

  /**
   * Reports the path of the ZooKeeper node for table layout update locking.
   *
   * @param tableURI Reports the path of the ZooKeeper node used to create locks for table layout
   *     updates.
   * @return the path of the ZooKeeper node used to create locks for table layout updates.
   */
  public static File getTableLayoutUpdateLock(KijiURI tableURI) {
    return new File(getTableDir(tableURI), "layout_update_lock");
  }

  // -----------------------------------------------------------------------------------------------

  /** Underlying ZooKeeper client. */
  private final ZooKeeperClient mZKClient;

  // -----------------------------------------------------------------------------------------------

  /**
   * Initializes a new table layout monitor.
   *
   * @param zkClient ZooKeeper client.
   * @throws KeeperException on unrecoverable ZooKeeper error.
   */
  public TableLayoutMonitor(ZooKeeperClient zkClient) throws KeeperException {
    this.mZKClient = zkClient;
    this.mZKClient.createNodeRecursively(ROOT_ZOOKEEPER_PATH);
  }

  /**
   * Closes the monitor.
   */
  public void close() {
    this.mZKClient.close();
  }

  /**
   * Creates a tracker for a table layout.
   *
   * <p> The tracker must be opened and closed. </p>
   *
   * @param tableURI Tracks the layout of the table with this URI.
   * @param handler Handler invoked to process table layout updates.
   * @return a new layout tracker for the specified table.
   */
  public LayoutTracker newTableLayoutTracker(KijiURI tableURI, LayoutUpdateHandler handler) {
    return new LayoutTracker(tableURI, handler);
  }

  /**
   * Registers a new user of a table.
   *
   * @param tableURI Registers a user for the table with this URI.
   * @param userId ID of the user to register.
   * @param layoutId ID of the layout
   * @throws KeeperException on unrecoverable ZooKeeper error.
   */
  public void registerTableUser(KijiURI tableURI, String userId, String layoutId)
      throws KeeperException {

    final File usersDir = getTableUsersDir(tableURI);
    this.mZKClient.createNodeRecursively(usersDir);
    this.mZKClient.create(
        new File(usersDir, userId),
        Bytes.toBytes(layoutId),
        Ids.OPEN_ACL_UNSAFE,
        CreateMode.EPHEMERAL);
  }

  /**
   * Unregisters a table user.
   *
   * @param tableURI Unregisters the specified user for the table with this URI.
   * @param userId ID of the user to unregister.
   * @throws KeeperException on unrecoverable ZooKeeper error.
   */
  public void unregisterTableUser(KijiURI tableURI, String userId) throws KeeperException {
    final File usersDir = getTableUsersDir(tableURI);
    this.mZKClient.createNodeRecursively(usersDir);
    final File clientPath = new File(usersDir, userId);
    final Stat stat = this.mZKClient.exists(clientPath);
    this.mZKClient.delete(clientPath, stat.getVersion());
  }

  /**
   * Creates a lock for layout updates on the specified table.
   *
   * @param tableURI URI of the table to create a lock for.
   * @return a Lock for the table with the specified URI.
   *     The lock is not acquired at this point: the user must calli {@code Lock.lock()} and then
   *     release the lock with {@code Lock.unlock()}.
   */
  public Lock newTableLayoutUpdateLock(KijiURI tableURI) {
    return new ZooKeeperLock(this.mZKClient, getTableLayoutUpdateLock(tableURI));
  }

  /**
   * Creates a tracker for the users of a table.
   *
   * <p> The tracker must be opened and closed. </p>
   *
   * @param tableURI Tracks the users of the table with this URI.
   * @param handler Handler invoked to process updates to the users list of the specified table.
   * @return a new tracker for the users of the specified table.
   */
  public UsersTracker newTableUsersTracker(KijiURI tableURI, UsersUpdateHandler handler) {
    return new UsersTracker(tableURI, handler);
  }

  /**
   * Notifies the users of a table of a new layout.
   *
   * <p>
   *   The caller must ensure proper locking of table layout update operations through
   *   {@link #newTableLayoutUpdateLock(KijiURI)}.
   * </p>
   *
   * @param tableURI Notify the users of the table with this URI.
   * @param layout Encoded layout update for the table with the specified URI.
   * @param version of the current table layout.
   * @throws KeeperException on unrecoverable ZooKeeper error.
   */
  public void notifyNewTableLayout(KijiURI tableURI, byte[] layout, int version)
      throws KeeperException {
    final File layoutPath = getTableLayoutFile(tableURI);
    this.mZKClient.createNodeRecursively(layoutPath);
    // This should not be needed if we add a lock for layout updates.
    final Stat updateStat =
        this.mZKClient.setData(layoutPath, layout, version);
    LOG.info("Updated layout for table {}. Layout version is {}.",
        tableURI, updateStat.getVersion());
  }

  // -----------------------------------------------------------------------------------------------

  /** Interface for trackers of a table's layout. */
  public interface LayoutUpdateHandler {
    /**
     * Processes an update to the table layout.
     *
     * <p> If this method raises an unchecked exception, the tracking stops. </p>
     *
     * @param layout Layout update, as an encoded byte[].
     *     This is the update content of the layout ZooKeeper node.
     */
    // TODO(SCHEMA-412): Notifications for ZooKeeper disconnections.
    void update(byte[] layout);
  }

  /**
   * Tracks the layout of a table and reports updates to registered handlers.
   *
   * <p> The handler is always invoked in a separate thread. </p>
   */
  public final class LayoutTracker implements Closeable {
    private final LayoutUpdateHandler mHandler;
    private final KijiURI mTableURI;
    private final File mTableLayoutFile;
    private final LayoutWatcher mWatcher = new LayoutWatcher();
    private final Stat mLayoutStat = new Stat();
    private final AtomicBoolean mOpened = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /** Automatically re-registers for new layout updates. */
    private class LayoutWatcher implements Watcher {
      /** {@inheritDoc} */
      @Override
      public void process(WatchedEvent event) {
        if (!mClosed.get()) {
          registerWatcher();
        } else {
          LOG.debug("LayoutTracker is closed : dropping layout update.");
          // Do not re-register a watcher.
        }
      }
    }

    /**
     * Initializes a new layout tracker with the given update handler on the specified table.
     *
     * @param tableURI Tracks the table with this URI.
     * @param handler Handler to process table layout updates.
     */
    private LayoutTracker(KijiURI tableURI, LayoutUpdateHandler handler) {
      this.mTableURI = tableURI;
      this.mTableLayoutFile = getTableLayoutFile(tableURI);
      this.mHandler = handler;
    }

    /**
     * Starts the tracker.
     *
     * @throws KeeperException on ZooKeeper error.
     */
    public void open() throws KeeperException {
      Preconditions.checkState(!mOpened.getAndSet(true),
          "Cannot start LayoutTracker while already started.");

      // Always runs registerWatcher() in a separate thread:
      final Thread thread = new Thread() {
        /** {@inheritDoc} */
        @Override
        public void run() {
          registerWatcher();
        }
      };
      thread.start();
    }

    /**
     * Registers a ZooKeeper watcher for the specified table's layout.
     *
     * <p> Retries on ZooKeeper failure (no deadline, no limit). </p>
     * <p> Dies whenever an exception pops up while running a handler. </p>
     */
    private void registerWatcher() {
      try {
        final byte[] layoutUpdate =
            TableLayoutMonitor.this.mZKClient.getData(mTableLayoutFile, mWatcher, mLayoutStat);
        LOG.info("Received layout update for table {}: {}.",
            mTableURI, Bytes.toStringBinary(layoutUpdate));

        // This assumes handlers do not let exceptions pop up:
        mHandler.update(layoutUpdate);

      } catch (KeeperException ke) {
        LOG.error("Unrecoverable ZooKeeper error: {}", ke.getMessage());
        throw new RuntimeException(ke);
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      Preconditions.checkState(mOpened.get(),
          "Cannot stop a LayoutTracker that has not been started.");
      Preconditions.checkState(!mClosed.getAndSet(true),
          "Cannot stop a LayoutTracker multiple times.");
    }
  }

  // -----------------------------------------------------------------------------------------------

  /**
   * Interface for trackers of a table's users.
   */
  public interface UsersUpdateHandler {

    /**
     * Processes an update to the list of users of the Kiji table being tracked.
     *
     * <p> If this method raises an unchecked exception, the tracking stops. </p>
     *
     * @param users Updated list of users of the Kiji table being tracked.
     */
    // TODO(SCHEMA-412): Notifications for ZooKeeper disconnections.
    void update(List<String> users);
  }

  /**
   * Tracks users of a table.
   *
   * <p> Monitors the users of a table and reports updates to the registered handlers. </p>
   * <p> The handler is always invoked in a separate thread. </p>
   */
  public final class UsersTracker implements Closeable {
    private final UsersUpdateHandler mHandler;
    private final KijiURI mTableURI;
    private final File mUsersDir;
    private final UsersWatcher mWatcher = new UsersWatcher();
    private final Stat mStat = new Stat();
    private final AtomicBoolean mOpened = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /** Automatically re-registers for users updates. */
    private class UsersWatcher implements Watcher {
      /** {@inheritDoc} */
      @Override
      public void process(WatchedEvent event) {
        if (!mClosed.get()) {
          registerWatcher();
        } else {
          LOG.debug("LayoutTracker is closed : dropping layout update.");
          // Do not re-register a watcher.
        }
      }
    }

    /**
     * Initializes a new users tracker with the given update handler on the specified table.
     *
     * @param tableURI Tracks the users of the table with this URI.
     * @param handler Handler to process updates of the table users list.
     */
    private UsersTracker(KijiURI tableURI, UsersUpdateHandler handler) {
      this.mTableURI = tableURI;
      this.mUsersDir = getTableUsersDir(tableURI);
      this.mHandler = handler;
    }

    /**
     * Starts the tracker.
     */
    public void open() {
      final Thread thread = new Thread() {
        /** {@inheritDoc} */
        @Override
        public void run() {
          try {
            TableLayoutMonitor.this.mZKClient.createNodeRecursively(mUsersDir);
          } catch (KeeperException ke) {
            LOG.error("Unrecoverable ZooKeeper error: {}", ke.getMessage());
            throw new RuntimeException(ke);
          }
          Preconditions.checkState(!mOpened.getAndSet(false));
          registerWatcher();
        }
      };
      thread.start();
    }

    /**
     * Registers a ZooKeeper watcher for the specified table's users.
     *
     * <p> Retries on ZooKeeper failure (no deadline, no limit). </p>
     * <p> Dies whenever an exception pops up while running a handler. </p>
     */
    private void registerWatcher() {
      try {
        // Lists the children nodes of the users ZooKeeper node path for this table,
        // and registers a watcher for updates on the children list:
        final List<String> children =
            TableLayoutMonitor.this.mZKClient.getChildren(mUsersDir, mWatcher, mStat);
        LOG.info("Received users update for table {}: {}.", mTableURI, children);

        // This assumes handlers do not let exceptions pop up:
        mHandler.update(children);

      } catch (KeeperException ke) {
        LOG.error("Unrecoverable ZooKeeper error: {}", ke.getMessage());
        throw new RuntimeException(ke);
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws IOException {
      Preconditions.checkState(mOpened.get(),
          "Cannot stop a UsersTracker that has not been started.");
      Preconditions.checkState(!mClosed.getAndSet(true),
          "Cannot stop a UsersTracker multiple times.");
    }
  }

}
