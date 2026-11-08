plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.synapse"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Application Features
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	implementation("org.apache.commons:commons-csv:1.12.0")

	// PostgreSQL
	implementation("org.postgresql:postgresql:42.7.4")

	// OAuth2 Implementation Dependencies
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Jackson for JSON processing
	implementation("com.fasterxml.jackson.core:jackson-databind")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

	// LangChain4j
	implementation("dev.langchain4j:langchain4j-spring-boot-starter:0.36.2")
	implementation("dev.langchain4j:langchain4j-open-ai:0.36.2")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Lombok Compile Configuration
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// Spring Boot Unified Test Framework (Includes WebMvcTest and Validation assertions)
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")

	// Test Lombok Scopes
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	environment(System.getenv())
}

tasks.bootRun {
	environment(System.getenv())
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
	environment(System.getenv())
}
