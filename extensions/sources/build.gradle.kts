plugins {
  `java-library`
  `maven-publish`
}

val moduleName = "lavaplayer-ext-sources"
version = project(":main").version

dependencies {
  compileOnly("com.github.devoxin.lavaplayer:lavaplayer:1.9.1")
  compileOnly("commons-io:commons-io:2.16.1")
  compileOnly("org.mozilla:rhino-engine:1.7.14")
  compileOnly("org.jsoup:jsoup:1.17.2")
  compileOnly("com.grack:nanojson:1.7")
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
