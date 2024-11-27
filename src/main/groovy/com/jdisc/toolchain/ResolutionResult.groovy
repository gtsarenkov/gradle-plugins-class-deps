package com.jdisc.toolchain

import java.util.zip.ZipEntry

class ResolutionResult implements Comparable<ResolutionResult> {
    final String canonicalClassName;
    final File baseFile
    final URI file
    final String relativePath

    ResolutionResult(String canonicalClassName, File baseDir, File file, String relativePath) {
        this.canonicalClassName = canonicalClassName
        this.baseFile = baseDir
        this.file = file.toURI()
        this.relativePath = relativePath
    }

    ResolutionResult(String canonicalClassName, File jarFile, ZipEntry file, String relativePath) {
        this.canonicalClassName = canonicalClassName
        this.baseFile = jarFile
        this.file = new URI("jar:file:" + jarFile.toPath().toUri().getPath() + "!/" + file.name)
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