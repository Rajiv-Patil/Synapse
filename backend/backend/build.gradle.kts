plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.synapse"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
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

	implementation("org.apache.commons:commons-csv:1.12.0")

	// OAuth2 Implementation Dependencies
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// LangChain4j (temporarily commented out)
	// implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.20.0-beta30")
	// implementation("dev.langchain4j:langchain4j-open-ai-spring-boot-starter:1.20.0-beta30")
	// implementation("dev.langchain4j:langchain4j-bedrock:1.20.0")

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

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
	environment(System.getenv())
}
