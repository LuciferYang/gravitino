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
evaluationDependsOn(":spark-connector:spark4-common")

plugins {
  `maven-publish`
  id("java")
  id("idea")
  alias(libs.plugins.shadow)
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
val scalaCollectionCompatVersion: String = libs.versions.scala.collection.compat.get()
val jerseyVersion: String = libs.versions.jersey.get()
val javaxWsRsVersion: String = libs.versions.javax.ws.rs.api.get()
// The HK2 line that Jersey 2.x is built against (javax.inject annotations).
val hk2Version: String = "2.6.1"
// jakarta.validation-api 2.x still ships the javax.validation packages; 3.x renames them.
val jakartaValidationVersion: String = "2.0.2"
val artifactName = "${rootProject.name}-spark-${sparkMajorVersion}_$scalaVersion"

// The ITs run an embedded Gravitino server, which serves REST on Jetty 9 (javax.servlet) with
// Jersey 2 (javax.ws.rs) and HK2 2.x (javax.inject). Spark 4.0 brings the jakarta flavor of these
// transitively via spark-hive, and Gradle's conflict resolution upgrades the server's copies:
// Jersey 2.41 -> 3.0.18 (Jetty 9 then rejects the ServletContainer: "is not a javax.servlet.Servlet"),
// HK2 2.6.1 -> 3.0.6 (HK2 3 reads jakarta.inject annotations, so Jersey 2's javax.inject-annotated
// RequestContext is no longer seen as a singleton), and jakarta.validation-api 2.0.2 -> 3.0.2
// (the 2.x jar still holds the javax.validation packages Jersey 2 looks up). Pin the test classpath
// back to the javax flavor. Tests only: the Spark 4.0 connector runtime jar bundles neither Jersey
// nor the Gravitino server.
configurations.testRuntimeClasspath {
  resolutionStrategy {
    force("javax.ws.rs:javax.ws.rs-api:$javaxWsRsVersion")
    force("org.glassfish.jersey.core:jersey-server:$jerseyVersion")
    force("org.glassfish.jersey.core:jersey-common:$jerseyVersion")
    force("org.glassfish.jersey.core:jersey-client:$jerseyVersion")
    force("org.glassfish.jersey.containers:jersey-container-servlet:$jerseyVersion")
    force("org.glassfish.jersey.containers:jersey-container-servlet-core:$jerseyVersion")
    force("org.glassfish.jersey.containers:jersey-container-jetty-http:$jerseyVersion")
    force("org.glassfish.jersey.inject:jersey-hk2:$jerseyVersion")
    force("org.glassfish.hk2:hk2-api:$hk2Version")
    force("org.glassfish.hk2:hk2-locator:$hk2Version")
    force("org.glassfish.hk2:hk2-utils:$hk2Version")
    force("org.glassfish.hk2.external:aopalliance-repackaged:$hk2Version")
    force("org.glassfish.hk2.external:jakarta.inject:$hk2Version")
    force("jakarta.validation:jakarta.validation-api:$jakartaValidationVersion")
  }
}

dependencies {
  // spark4-common carries the shared spark-common sources compiled against the Spark 4 API plus the
  // Spark 4 flavor of the version-incompatible classes. The 4.0 line builds on it, not the 3.x
  // version modules.
  implementation(project(":spark-connector:spark4-common"))
  compileOnly("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:$kyuubiVersion")
  compileOnly("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion") {
    exclude("com.fasterxml.jackson")
  }
  compileOnly(project(":clients:client-java-runtime", configuration = "shadow"))
  compileOnly("org.apache.iceberg:iceberg-spark-runtime-${sparkMajorVersion}_$scalaVersion:$icebergVersion")

  testImplementation(project(":api")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":catalogs:catalog-jdbc-common")) {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(project(":catalogs:hive-metastore-common")) {
    exclude("*")
  }
  testImplementation(project(":clients:client-java")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":core")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":common")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":integration-test-common", "testArtifacts")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":server")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":server-common")) {
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":spark-connector:spark4-common")) {
    exclude("com.fasterxml.jackson")
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }
  testImplementation(project(":spark-connector:spark-common", "testArtifacts")) {
    exclude("com.fasterxml.jackson")
    exclude("org.apache.logging.log4j")
    exclude("org.slf4j")
  }

  testImplementation(libs.awaitility)
  // Spark 3.x pulled javax.ws.rs transitively via spark-hive; Spark 4.0 no longer does, so the
  // embedded MiniGravitino server started by the ITs needs the JAX-RS API added explicitly.
  testImplementation(libs.javax.ws.rs.api)
  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mysql.driver)
  testImplementation(libs.postgresql.driver)
  testImplementation(libs.testcontainers)

  testImplementation(libs.hive2.common) {
    exclude("com.sun.jersey")
    exclude("org.apache.curator")
    exclude("org.apache.logging.log4j")
    exclude("org.apache.hadoop")
    exclude("org.eclipse.jetty.aggregate", "jetty-all")
    exclude("org.eclipse.jetty.orbit", "javax.servlet")
  }
  testImplementation(libs.hive2.metastore) {
    exclude("co.cask.tephra")
    exclude("com.github.joshelser")
    exclude("com.google.code.findbugs", "jsr305")
    exclude("com.google.code.findbugs", "sr305")
    exclude("com.tdunning", "json")
    exclude("com.zaxxer", "HikariCP")
    exclude("com.sun.jersey")
    exclude("io.dropwizard.metrics")
    exclude("javax.transaction", "transaction-api")
    exclude("org.apache.avro")
    exclude("org.apache.curator")
    exclude("org.apache.hbase")
    exclude("org.apache.hadoop")
    exclude("org.apache.hive", "hive-common")
    exclude("org.apache.hive", "hive-shims")
    exclude("org.apache.logging.log4j")
    exclude("org.apache.parquet", "parquet-hadoop-bundle")
    exclude("org.apache.zookeeper")
    exclude("org.eclipse.jetty.aggregate", "jetty-all")
    exclude("org.eclipse.jetty.orbit", "javax.servlet")
    exclude("org.slf4j")
  }

  testImplementation("org.apache.iceberg:iceberg-core:$icebergVersion")
  testImplementation("org.apache.iceberg:iceberg-spark-runtime-${sparkMajorVersion}_$scalaVersion:$icebergVersion")
  testImplementation("org.apache.iceberg:iceberg-hive-metastore:$icebergVersion")
  testImplementation("org.apache.kyuubi:kyuubi-spark-connector-hive_$scalaVersion:$kyuubiVersion")
  testImplementation("org.apache.spark:spark-hive_$scalaVersion:$sparkVersion") {
    exclude("org.glassfish.jersey.core")
    exclude("org.glassfish.jersey.containers")
    exclude("org.glassfish.jersey.inject")
    exclude("com.sun.jersey")
    exclude("com.fasterxml.jackson")
    exclude("com.fasterxml.jackson.core")
  }
  testImplementation("org.scala-lang.modules:scala-collection-compat_$scalaVersion:$scalaCollectionCompatVersion")
  testImplementation("org.apache.spark:spark-catalyst_$scalaVersion:$sparkVersion")
  testImplementation("org.apache.spark:spark-core_$scalaVersion:$sparkVersion")
  testImplementation("org.apache.spark:spark-sql_$scalaVersion:$sparkVersion")

  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
  val skipITs = project.hasProperty("skipITs")
  if (skipITs) {
    exclude("**/integration/test/**")
  } else {
    // The ITs boot an embedded Gravitino server whose aux services (Iceberg/Lance REST) pull in the
    // JAX-RS stack, plus the catalog jars they exercise. Mirror the 3.x modules' dependsOn set.
    dependsOn(tasks.jar)
    dependsOn(":catalogs:catalog-lakehouse-iceberg:jar")
    dependsOn(":catalogs:catalog-hive:jar")
    dependsOn(":iceberg:iceberg-rest-server:jar")
    dependsOn(":lance:lance-rest-server:jar")
    dependsOn(":catalogs:catalog-jdbc-mysql:jar")
    dependsOn(":catalogs:catalog-jdbc-postgresql:jar")

    // Spark 4.0's web UI serves its REST API with Jersey 3 (jakarta.servlet) on its shaded Jetty,
    // while the embedded Gravitino server needs Jersey 2 (javax.servlet) on Jetty 9 -- the two
    // cannot coexist on one classpath, and the block above pins Jersey to 2.x for the server. The
    // ITs never look at the UI, so turn it off. SparkConf loads defaults from "spark.*" system
    // properties, so this reaches the session without touching the shared SparkEnvIT.
    systemProperty("spark.ui.enabled", "false")
  }
}

tasks.clean {
  delete("derby.log")
  delete("metastore_db")
  delete("spark-warehouse")
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
