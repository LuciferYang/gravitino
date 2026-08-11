/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

// Spark 4 is Scala 2.13 only.
val scalaVersion: String = "2.13"
val sparkVersion: String = libs.versions.spark40.get()
val sparkMajorVersion: String = sparkVersion.substringBeforeLast(".")
val icebergVersion: String = libs.versions.iceberg4spark40.get()
val kyuubiVersion: String = libs.versions.kyuubi4spark.get()
val artifactName = "${rootProject.name}-spark4-common_$scalaVersion"

// Reuse the shared sources from spark-common, compiled here against the Spark 4 API. The
// version-incompatible classes come from spark-common/src/main/spark4 (the Spark 3.x flavor lives
// under spark-common/src/main/spark3 and is compiled by the spark-common module instead).
val sparkCommonDir = project(":spark-connector:spark-common").projectDir

sourceSets {
  named("main") {
    java {
      setSrcDirs(
        listOf(
          "$sparkCommonDir/src/main/java",
          "$sparkCommonDir/src/main/spark4"
        )
      )
    }
    resources {
      setSrcDirs(listOf("$sparkCommonDir/src/main/resources"))
    }
  }
}

// This module compiles spark-common's sources, which live outside its own project dir; Spotless
// refuses cross-project targets. spark-common formats those files itself, so scope Spotless here to
// this project's own tree (currently empty) instead. Same approach as the trino-connector modules.
plugins.withId("com.diffplug.spotless") {
  configure<SpotlessExtension> {
    java {
      target(project.fileTree("src") { include("**/*.java") })
    }
  }
}

dependencies {
  implementation(project(":catalogs:catalog-common")) {
    exclude("org.apache.logging.log4j")
  }
  implementation(libs.guava)
  implementation(libs.caffeine)

  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.iceberg:iceberg-spark-runtime-${sparkMajorVersion}_$scalaVersion:$icebergVersion")
  compileOnly(libs.aws.glue)
  compileOnly("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:$kyuubiVersion")
  compileOnly("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
  compileOnly("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

  annotationProcessor(libs.lombok)
  compileOnly(libs.lombok)
}

tasks.withType<Jar> {
  archiveBaseName.set(artifactName)
}

publishing {
  publications {
    withType<MavenPublication>().configureEach {
      artifactId = artifactName
    }
  }
}
