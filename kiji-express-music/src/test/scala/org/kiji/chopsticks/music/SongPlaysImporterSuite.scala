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

package org.kiji.chopsticks.music

import scala.collection.mutable.Buffer

import com.twitter.scalding._

import org.kiji.chopsticks._
import org.kiji.chopsticks.DSL._
import org.kiji.schema.EntityId

/**
 * A test that ensures the song plays importer can import records of tracks being played into a
 * users table.
 */
class SongPlaysImporterSuite extends KijiSuite {

  // Get a Kiji to use for the test and record the Kiji URI of the users table we'll test against.
  val kiji = makeTestKiji("SongPlaysImporterSuite")
  val tableURI = kiji.getURI().toString + "/users"

  // Execute the DDL shell commands in music-schema.ddl to create the tables for the music
  // tutorial, including the users table.
  executeDDLResource(kiji, "org/kiji/chopsticks/music/music-schema.ddl")

  // Create some fake track-play records for a user.
  val testInput =
      (0, """{ "user_id" : "user-0", "play_time" : "0", "song_id" : "song-0" }""" ) ::
      (1, """{ "user_id" : "user-0", "play_time" : "1", "song_id" : "song-1" }""") ::
      (2, """{ "user_id" : "user-0", "play_time" : "2", "song_id" : "song-2" }""") :: Nil


  /**
   * Validates the output generated by a test of the song plays importer.
   *
   * This function accepts the output of a test as a buffer of tuples,
   * where the first tuple element is an entity id for a row that was written to by the job,
   * and the second tuple element is a map of column values written to that row. We validate the
   * data that should have been written for the single test user.
   *
   * @param generatedPlays contains a tuple for each row written to by the importer.
   */
  def validateTest(generatedPlays: Buffer[(EntityId, KijiSlice[String])]) {
    // One row for one user.
    assert(1 == generatedPlays.size)
    // Check contents of playlist.
    val playlist = generatedPlays(0)._2
    playlist.orderChronologically().cells.foreach { cell =>
      assert("song-" + cell.version == cell.datum)
    }
  }

  // Run a test of the import job, running in Cascading's local runner.
  test("SongPlaysImporter puts JSON play records into the user table using local mode.") {
    JobTest(new SongPlaysImporter(_))
        .arg("table-uri", tableURI)
        .arg("input", "song-plays.json")
        .source(TextLine("song-plays.json"), testInput)
        .sink(KijiOutput(tableURI, 'playTime)('songId -> "info:track_plays"))(validateTest)
        .run
        .finish
  }

  // Run a test of the import job, running in Hadoop's local job runner.
  test("SongPlaysImporter puts JSON play records into the user table using Hadoop mode.") {
    JobTest(new SongPlaysImporter(_))
    .arg("table-uri", tableURI)
    .arg("input", "song-plays.json")
    .source(TextLine("song-plays.json"), testInput)
    .sink(KijiOutput(tableURI, 'playTime)('songId -> "info:track_plays"))(validateTest)
    .runHadoop
    .finish
  }
}
