package com.jdisc.toolchain

import java.io.File

class ResolutionResult implements Comparable<ResolutionResult> {
    final String canonicalClassName;
    final File baseFile
    final File file
    final String relativePath

    ResolutionResult(String canonicalClassName, File baseFile, File file, String relativePath) {
        this.canonicalClassName = canonicalClassName
        this.baseFile = baseFile
        this.file = file
        this.relativePath = relativePath
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) return true
        if (!(o instanceof ResolutionResult)) return false
        ResolutionResult that = (ResolutionResult) o
        return canonicalClassName.equals(that.canonicalClassName) && file.equals(that.file)
    }

    @Override
    int hashCode() {
        return file.hashCode()
    }

    int compareTo(ResolutionResult that) {
        return canonicalClassName.compareTo(that.canonicalClassName)
    }
}