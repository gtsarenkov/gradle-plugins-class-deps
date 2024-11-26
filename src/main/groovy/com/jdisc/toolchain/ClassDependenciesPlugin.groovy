/*
 * This source file was generated by the Gradle 'init' task
 */
package com.jdisc.toolchain

import org.gradle.api.Project
import org.gradle.api.Plugin

/**
 * A simple 'hello world' plugin.
 */
class ClassDependenciesPlugin implements Plugin<Project> {
    void apply(Project project) {
        // Register a task
//        project.tasks.register("greeting") {
//            doLast {
//                println("Hello from plugin 'com.jdisc.toolchain.class-dependencies'")
//            }
//        }
        project.afterEvaluate {
            println("ClassDependenciesPlugin has been applied.")
        }
    }
}
