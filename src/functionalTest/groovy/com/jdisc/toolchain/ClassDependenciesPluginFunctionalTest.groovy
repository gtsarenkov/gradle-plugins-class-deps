package com.jdisc.toolchain

import spock.lang.Specification
import spock.lang.TempDir
import org.gradle.testkit.runner.GradleRunner

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

/**
 * A simple functional test for the 'com.jdisc.toolchain.class-dependencies' plugin.
 */
class ClassDependenciesPluginFunctionalTest extends Specification {
    @TempDir
    private File projectDir

    private getBuildFile() {
        new File(projectDir, "build.gradle")
    }

    private getSettingsFile() {
        new File(projectDir, "settings.gradle")
    }

    void cleanup() {
    }

    void cleanupSpec() {
    }

    def "can run task"() {
        given:
        settingsFile << ""
        buildFile << """
plugins {
    id('com.jdisc.toolchain.class-dependencies')
}

configurations {
  classpath {
    canBeResolved = true
  }
}

dependencies {
  classpath gradleApi()
}

import com.jdisc.toolchain.tasks.AddFilesToOutputTask

tasks.register("greeting", AddFilesToOutputTask) {
    mainClass = 'com.jdisc.toolchain.tasks.AddFilesToOutputTaskTest'
    classpath = files().from(project.findProperty("classpathDir").split(';'), configurations.classpath)   
    outputDirs = files(project.layout.buildDirectory.dir("out").get().asFile)
    excludePatterns = ['**/.class', '**/*.java', '**/Thumbs.db', '**/*.mkd', '**/*.md', '**/*.css', 'com/gitblit/wicket/**', 'org/codehaus/groovy/**']
    ignoreMissingClassFile = true
    doLast {
        println "Hello from plugin 'com.jdisc.toolchain.class-dependencies'"
    }
}
"""

        when:
        def runner = GradleRunner.create()
        runner.withDebug(true)
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("--info", "greeting", "-PclasspathDir=${new File("build/classes/java/test").absolutePath};${new File("build/classes/groovy/main").absolutePath};${new File("build/classes/java/main").absolutePath}")
        runner.withProjectDir(projectDir)
        def result = runner.build()

        then:
        result.output.contains("Hello from plugin 'com.jdisc.toolchain.class-dependencies'")
        result.task(':greeting').outcome == SUCCESS
        new File(projectDir, "build/out/com/jdisc/toolchain/tasks/AddFilesToOutputTaskTest.class").exists()
    }
}
