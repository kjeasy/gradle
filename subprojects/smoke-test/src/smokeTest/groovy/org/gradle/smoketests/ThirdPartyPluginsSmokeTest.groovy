/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.smoketests

import org.gradle.util.ports.ReleasingPortAllocator
import org.gradle.vcs.fixtures.GitRepository
import org.junit.Rule
import spock.lang.Issue

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class ThirdPartyPluginsSmokeTest extends AbstractSmokeTest {

    @Rule final ReleasingPortAllocator portAllocator = new ReleasingPortAllocator()

    def 'shadow plugin'() {
        given:
        buildFile << """
            import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

            plugins {
                id 'java' // or 'groovy' Must be explicitly applied
                id 'com.github.johnrengelman.shadow' version '1.2.3'
            }

            ${jcenterRepository()}

            dependencies {
                compile 'commons-collections:commons-collections:3.2.2'
            }

            shadowJar {
                transform(ServiceFileTransformer)

                manifest {
                    attributes 'Test-Entry': 'PASSED'
                }
            }
            """.stripIndent()

        when:
        def result = runner('shadowJar').build()

        then:
        result.task(':shadowJar').outcome == SUCCESS
    }

    def 'asciidoctor plugin'() {
        given:
        buildFile << """
            buildscript {
                ${jcenterRepository()}
                dependencies {
                    classpath "org.asciidoctor:asciidoctor-gradle-plugin:1.5.3"                
                }
            }

            apply plugin: 'org.asciidoctor.gradle.asciidoctor'
            """.stripIndent()

        file('src/docs/asciidoc/test.adoc') << """
            = Line Break Doc Title
            :hardbreaks:

            Rubies are red,
            Topazes are blue.
            """.stripIndent()

        when:
        runner('asciidoc').build()

        then:
        file('build/asciidoc').isDirectory()
    }

    def 'docker plugin'() {
        given:
        buildFile << """
            plugins {
                id 'java'
                id 'application'
                id "com.bmuschko.docker-java-application" version "3.0.6"
            }

            mainClassName = 'org.gradle.JettyMain'

            docker {
                javaApplication {
                    baseImage = 'dockerfile/java:openjdk-7-jre'
                    port = 9090
                    tag = 'jettyapp:1.115'
                }
            }
            """.stripIndent()

        when:
        def result = runner(':dockerCopyDistResources').build()

        then:
        result.task(':dockerCopyDistResources').outcome == SUCCESS
    }

    def 'spring dependency management plugin'() {
        given:
        buildFile << """
            plugins {
                id 'java'
                id 'io.spring.dependency-management' version '1.0.1.RELEASE'
            }

            ${mavenCentralRepository()}

            dependencyManagement {
                dependencies {
                    dependency 'org.springframework:spring-core:4.0.3.RELEASE'
                    dependency group: 'commons-logging', name: 'commons-logging', version: '1.1.2'
                }
            }

            dependencies {
                compile 'org.springframework:spring-core'
            }
            """.stripIndent()

        when:
        def result = runner("dependencies", "--configuration", "compile").build()

        then:
        result.output.contains('org.springframework:spring-core: -> 4.0.3.RELEASE')
    }

    def 'spring boot plugin'() {
        given:
        buildFile << """
            buildscript {
                ${mavenCentralRepository()}
                dependencies {
                    classpath('org.springframework.boot:spring-boot-gradle-plugin:1.5.2.RELEASE')
                }
            }

            apply plugin: 'spring-boot'
        """.stripIndent()

        file('src/main/java/example/Application.java') << """
            package example;

            public class Application {
                public static void main(String[] args) {}
            }
        """.stripIndent()

        when:
        def result = runner('build').build()

        then:
        result.task(':findMainClass').outcome == SUCCESS
        result.task(':bootRepackage').outcome == SUCCESS
    }

    @Issue("gradle/gradle#2480")
    def "spring dependency management plugin and BOM"() {
        given:
        buildFile << """
            buildscript {    
                ${mavenCentralRepository()}
            }
            
            plugins { 
                id 'java'
                id 'io.spring.dependency-management' version '1.0.0.RELEASE' 
            }
            
            ${mavenCentralRepository()}
            
            dependencies {
                compile('org.springframework.boot:spring-boot-starter')
                testCompile('org.springframework.boot:spring-boot-starter-test')
            }
            
            dependencyManagement {
                imports { mavenBom("org.springframework.boot:spring-boot-dependencies:1.5.2.RELEASE") }
            }
            
            task resolveDependencies {
                doLast {
                    configurations.compile.files
                    configurations.testCompile.files
                }
            }
        """

        when:
        runner('resolveDependencies').build()

        then:
        noExceptionThrown()
    }

    def 'tomcat plugin'() {
        given:
        def httpPort = portAllocator.assignPort()
        def httpsPort = portAllocator.assignPort()
        def stopPort = portAllocator.assignPort()
        buildFile << """
            plugins {
                id "com.bmuschko.tomcat" version "2.2.5"
            }

            ${mavenCentralRepository()}

            dependencies {
                def tomcatVersion = '7.0.59'
                tomcat "org.apache.tomcat.embed:tomcat-embed-core:\${tomcatVersion}",
                       "org.apache.tomcat.embed:tomcat-embed-logging-juli:\${tomcatVersion}",
                       "org.apache.tomcat.embed:tomcat-embed-jasper:\${tomcatVersion}"
            }

            ext {
                tomcatStopPort = ${stopPort}
                tomcatStopKey = 'stopKey'
            }

            tomcat {
                httpPort = ${httpPort}
                httpsPort = ${httpsPort}
            }

            task integrationTomcatRun(type: com.bmuschko.gradle.tomcat.tasks.TomcatRun) {
                stopPort = tomcatStopPort
                stopKey = tomcatStopKey
                daemon = true
            }

            task integrationTomcatStop(type: com.bmuschko.gradle.tomcat.tasks.TomcatStop) {
                stopPort = tomcatStopPort
                stopKey = tomcatStopKey
            }

            task integrationTest(type: Test) {
                include '**/*IntegrationTest.*'
                dependsOn integrationTomcatRun
                finalizedBy integrationTomcatStop
            }

            test {
                exclude '**/*IntegrationTest.*'
            }
            """.stripIndent()

        expect:
        runner('integrationTest').build()
    }

    def 'gosu plugin'() { // Requires JDK 8 or later
        given:
        buildFile << """
            plugins {
                id 'org.gosu-lang.gosu' version '0.3.5'
            }

            apply plugin: 'org.gosu-lang.gosu'

            ${mavenCentralRepository()}

            dependencies {
                compile group: 'org.gosu-lang.gosu', name: 'gosu-core-api', version: '1.14.6'
            }
            """.stripIndent()

        file('src/main/gosu/example/Foo.gs') << """
            package example

            public class Foo {

              function doSomething(arg : String) : String {
                return "Hello, got the argument '\${arg}'"
              }
            }
            """.stripIndent()


        when:
        def result = runner('build').build()

        then:
        result.task(':compileGosu').outcome == SUCCESS
    }

    def 'xtend plugin'() {
        given:
        buildFile << """
            plugins {
                id "org.xtext.xtend" version "1.0.17"
            }

            ${jcenterRepository()}

            dependencies {
                compile 'org.eclipse.xtend:org.eclipse.xtend.lib:2.11.0'
            }
            """.stripIndent()

        file('src/main/java/HelloWorld.xtend') << """
            class HelloWorld {
                def static void main(String[] args) {
                    println("Hello World")
                }
            }
            """

        when:
        def result = runner('build').build()

        then:
        result.task(':generateXtext').outcome == SUCCESS
    }

    def 'org.ajoberstar.grgit plugin'() {
        given:
        GitRepository.init(testProjectDir.root)
        buildFile << """
            plugins {
                id "org.ajoberstar.grgit" version "2.0.1"
            }

            def sourceFile = file("sourceFile")

            task commit {
                doLast {
                    sourceFile.text = "hello world"
                    grgit.add(patterns: [ 'sourceFile' ])
                    grgit.commit {
                        message = "first commit"
                    }
                }
            }

            task tag {
                dependsOn commit
                doLast {
                    grgit.tag.add {
                        name = 'previous'
                        message = 'previous commit'
                    }

                    sourceFile.text = "goodbye world"
                    grgit.add(patterns: [ 'sourceFile' ])
                    grgit.commit {
                        message = "second commit"
                    }
                }
            }

            task checkout {
                dependsOn tag
                doLast {
                    assert sourceFile.text == 'goodbye world'
                    grgit.checkout {
                        branch = 'previous'
                    }
                    assert sourceFile.text == 'hello world'
                }
            }

            task release {
                dependsOn checkout
            }
        """.stripIndent()

        when:
        def result = runner('release').build()

        then:
        result.task(':commit').outcome == SUCCESS
        result.task(':tag').outcome == SUCCESS
        result.task(':checkout').outcome == SUCCESS
    }
}
