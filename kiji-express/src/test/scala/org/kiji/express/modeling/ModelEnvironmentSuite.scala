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

package org.kiji.express.modeling

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.io.Source

import org.scalatest.FunSuite

import org.kiji.express.Resources.doAndClose
import org.kiji.express.Resources.resourceAsString
import org.kiji.express.avro.AvroDataRequest
import org.kiji.express.avro.AvroModelEnvironment
import org.kiji.express.avro.ColumnSpec
import org.kiji.express.avro.KVStore
import org.kiji.express.avro.KvStoreType
import org.kiji.express.avro.Property
import org.kiji.schema.KijiDataRequest
import org.kiji.schema.util.FromJson
import org.kiji.schema.util.ToJson

class ModelEnvironmentSuite extends FunSuite {
  val validDefinitionLocation: String =
      "src/test/resources/modelEnvironments/valid-model-environment.json"
  val invalidVersionDefinitionLocation: String =
      "src/test/resources/modelEnvironments/invalid-version-model-environment.json"
  val invalidNameDefinitionLocation: String =
      "src/test/resources/modelEnvironments/invalid-name-model-environment.json"
  val invalidProtocolDefinitionLocation: String =
      "src/test/resources/modelEnvironments/invalid-protocol-version-model-environment.json"

  test("ModelEnvironment can be created from a path to a valid JSON file.") {
    val expectedRequest: KijiDataRequest = {
      val builder = KijiDataRequest.builder().withTimeRange(0, 38475687)
      builder.newColumnsDef().withMaxVersions(3).add("info", "in")
      builder.build()
    }

    val expectedKvstores: Seq[KVStore] = {
      val property: Property = Property
          .newBuilder()
          .setName("path")
          .setValue("/usr/src/and/so/on")
          .build()
      val store: KVStore = KVStore
          .newBuilder()
          .setName("side_data")
          .setStoreType(KvStoreType.AVRO_KV)
          .setProperties(Seq(property).asJava)
          .build()

      Seq(store)
    }

    val environment: ModelEnvironment = ModelEnvironment.fromJsonFile(validDefinitionLocation)

    assert("myRunProfile" === environment.name)
    assert("1.0.0" === environment.version)
    assert(expectedRequest === environment.extractEnvironment.dataRequest)
    assert(expectedKvstores === environment.extractEnvironment.kvstores)
    assert("info:out" === environment.scoreEnvironment.outputColumn)
    assert(expectedKvstores === environment.scoreEnvironment.kvstores)
  }

  test("ModelEnvironment can write out JSON.") {
    val originalJson: String = doAndClose(Source.fromFile(validDefinitionLocation)) { source =>
      source.mkString
    }
    val originalAvroObject: AvroModelEnvironment = FromJson
        .fromJsonString(originalJson, AvroModelEnvironment.SCHEMA$)
        .asInstanceOf[AvroModelEnvironment]
    val environment = ModelEnvironment.fromJson(originalJson)
    val newJson: String = environment.toJson()
    assert(ToJson.toAvroJsonString(originalAvroObject) === newJson)
  }

  test("ModelEnvironment can validate the version.") {
    val thrown = intercept[ModelEnvironmentValidationException] {
      ModelEnvironment.fromJsonFile(invalidVersionDefinitionLocation)
    }
    assert("Model environment version strings must match the regex \"[0-9]+(.[0-9]+)*\" " +
        "(1.0.0 would be valid)." === thrown.getMessage)
  }

  test("ModelEnvironment can validate the name.") {
    val thrown = intercept[ModelEnvironmentValidationException] {
      ModelEnvironment.fromJsonFile(invalidNameDefinitionLocation)
    }
    assert("The name of the model environment can not be the empty string." === thrown.getMessage)
  }

  test("ModelEnvironment can validate the protocol version.") {
    val thrown = intercept[ModelEnvironmentValidationException] {
      ModelEnvironment.fromJsonFile(invalidProtocolDefinitionLocation)
    }
    assert("\"model_environment-0.1.0\" is the maximum protocol version supported. " +
        "The provided model environment is of protocol version: \"model_environment-0.2.0\""
        === thrown.getMessage)
  }
}
