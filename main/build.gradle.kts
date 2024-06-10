plugins {
  `java-library`
  groovy
  `maven-publish`
}

val moduleName = "lavaplayer"
version = "1.10.0"

dependencies {
  val jacksonVersion = "2.17.1"

  api("com.sedmelluq:lava-common:1.1.2")
  implementation("com.github.devoxin:lavaplayer-natives-fork:2.0.0")
  implementation("org.mozilla:rhino-engine:1.7.14")
  api("org.slf4j:slf4j-api:1.7.25")

  api("org.apache.httpcomponents:httpclient:4.5.14")
  implementation("commons-io:commons-io:2.16.1")

  api("com.fasterxml.jackson.core:jackson-core:$jacksonVersion")
  api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

  implementation("org.jsoup:jsoup:1.17.2")
  implementation("com.grack:nanojson:1.7")

  testImplementation("org.codehaus.groovy:groovy:2.5.5")
  testImplementation("org.spockframework:spock-core:1.2-groovy-2.5")
  testImplementation("ch.qos.logback:logback-classic:1.2.9")
  testImplementation("com.sedmelluq:lavaplayer-test-samples:1.3.11")
}

tasks.jar {
  exclude("natives")
}

val updateVersion by tasks.registering {
  File("$projectDir/src/main/resources/com/sedmelluq/discord/lavaplayer/tools/version.txt").let {
    it.parentFile.mkdirs()
    it.writeText(version.toString())
  }
}

tasks.classes.configure {
  finalizedBy(updateVersion)
}

val sourcesJar by tasks.registering(Jar::class) {
  archiveClassifier.set("sources")
  from(sourceSets["main"].allSource)
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifactId = moduleName
      artifact(sourcesJar)
    }
  }
}
