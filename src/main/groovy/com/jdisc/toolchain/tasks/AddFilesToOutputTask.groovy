package com.jdisc.toolchain.tasks

import com.jdisc.toolchain.ResolutionResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.text.MessageFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@CacheableTask
class AddFilesToOutputTask extends DefaultTask {
    @Input
    final Property<String> mainClass = project.objects.property(String)

    @Input
    final SetProperty<String> classes = project.objects.setProperty(String)

    @Input
    final SetProperty<String> excludes = project.objects.setProperty(String).convention(List.of(
            "java.",
            "javax.",
            "sun.",
            "sunw.",
            "com.sun.",
            "org.omg.",
            "org.w3c.",
            "com.ibm.jvm.",

            "groovy.",

            "boolean",
            "byte",
            "char",
            "short",
            "int",
            "long",
            "float",
            "double"
    ))

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    final ConfigurableFileCollection classpath = project.objects.fileCollection()

    @OutputDirectories
    final ConfigurableFileCollection outputDirs = project.objects.fileCollection()

    @Input
    final ListProperty<String> excludePatterns = project.objects.listProperty(String)

    @Input
    final Property<Boolean> ignoreMissingClassFile = project.objects.property(Boolean.class).convention(false)

    private final Provider<ConfigurableFileCollection> outputFiles = project.provider { project.objects.fileCollection() }

    @OutputFiles
    Provider<ConfigurableFileCollection> getOutputFiles() {
        return outputFiles
    }

    @TaskAction
    void addFiles() {
        if (classpath.isEmpty()) {
            throw new GradleException("Lookup classpath for task ${getName()} must not be empty")
        }
        classpath.each { it -> logger.info("${getName()}:${it.exists() ?: ' not'} exists lookup classpath: ${it.absolutePath}") }
        Set<ResolutionResult> classFileData = findClassFiles([mainClass.get()] + this.classes.get())
        Set<ResolutionResult> resolvedFiles = resolveImportedClasses(classFileData)

        processOutputFiles(resolvedFiles)
    }

    protected Set<ResolutionResult> findClassFiles(Collection<String> classNames) {
        Set<ResolutionResult> classFileData = new HashSet<>()
        Set<File> jarFiles = new TreeSet<>()
        def regex = /^(\[L)|(;$)|(\[]$)/

        classNames.each { classNameEnc ->
            // Strip JVM internal class name mangling
            String className = classNameEnc.replaceAll(regex, '')
            boolean notFound = true
            String classFilePath = className.replace('.', '/') + ".class"
            classpath.findAll { file -> !outputDirs.contains(file) }.each { file ->
                if (file.isDirectory()) {
                    File potentialFile = new File(file, classFilePath)
                    if (potentialFile.exists() && !isExcluded(potentialFile)) {
                        classFileData.add(new ResolutionResult(className, file, potentialFile, potentialFile.path - file.path))
                        notFound = false
                    }
                } else if (file.name.endsWith('.jar')) {
                    ZipFile zipFile = new ZipFile(file)
                    if (zipFile.entries().any { ZipEntry entry -> entry.name == classFilePath && !isExcluded(entry) }) {
                        jarFiles.add(file)
                        notFound = false
                    }
                }
            }
            if (notFound) {
                if (ignoreMissingClassFile) {
                    logger.warn(MessageFormat.format("Class file {0} cannot be found in {1}", classFilePath, classpath.asPath))
                } else {
                    logger.error(MessageFormat.format("Class file {0} cannot be found in {1}", classFilePath, classpath.asPath))
                    throw new GradleException(MessageFormat.format("Class file {0} cannot be found", classFilePath))
                }
            }
        }

        jarFiles.each { file -> outputFiles.get().from(file) }

        classFileData.removeAll { result -> outputFiles.get().contains(result.file) }

        return classFileData
    }


    protected Set<ResolutionResult> resolveImportedClasses(Set<ResolutionResult> classFileData) {
        Set<ResolutionResult> resolvedFiles = new TreeSet()
        Set<ResolutionResult> newClasses = new TreeSet(classFileData)

        while (!newClasses.isEmpty()) {
            Set<ResolutionResult> currentClassFiles = new HashSet<>(newClasses)
            newClasses.clear()

            currentClassFiles.each { result ->
                if (!resolvedFiles.contains(result)) {
                    resolvedFiles.add(result)

                    Set<String> importedClasses = findImportedClasses(result.file)
                    Set<ResolutionResult> resolvedImportedFiles = findClassFiles(importedClasses)

                    newClasses.addAll(resolvedImportedFiles.findAll { !resolvedFiles.contains(it) })
                }
            }
        }

        return resolvedFiles
    }

    protected Set<String> findImportedClasses(File classFile) {
        ClassNode classNode = new ClassNode()
        ClassReader classReader = new ClassReader(classFile.bytes)
        classReader.accept(classNode, 0)

        Set<String> importedClasses = new TreeSet()

        String relativePath = classNode.name
        // Collect method references
        classNode.methods.each { method ->
            method.instructions.each { instruction ->
                if (instruction instanceof MethodInsnNode) {
                    def value = instruction.owner.replace('/', '.')
                    importedClasses.add(value)
                    logger.info("Method instruction found ${relativePath}: ${value} ${instruction.owner}.${instruction.name}")
                } else if (instruction instanceof FieldInsnNode) {
                    def value = Type.getType(instruction.desc).className.replace('/', '.')
                    importedClasses.add(value)
                    logger.info("Field instruction found ${relativePath}: ${value} ${instruction.owner}.${instruction.name}")
                } else if (instruction instanceof LdcInsnNode && instruction.cst instanceof Type) {
                    def value = instruction.cst.className.replace('/', '.')
                    importedClasses.add(value)
                    logger.info("LDC Type instruction found ${relativePath}: ${value} ${instruction.cst.className}")
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    def value = instruction.bsm.owner.replace('/', '.')
                    importedClasses.add(value)
                    logger.info("InvokeDynamic instruction found ${relativePath}: ${value} ${instruction.bsm.name}")
                }
            }
        }
        // Collect field references
        classNode.fields.each { field ->
            def value = Type.getType(field.desc).className.replace('/', '.')
            importedClasses.add(value)
            logger.info("Field found ${relativePath}: ${value} ${field.name}")
        }

        classNode.interfaces.each {intfName ->
            def value = intfName.replace('/', '.')
            importedClasses.add(value)
            logger.info("Interfaces found ${relativePath}: ${value}")
        }

        // Collect inner classes
        classNode.innerClasses.each { innerClass ->
            if (innerClass.name) {
                def value = innerClass.name.replace('/', '.')
                importedClasses.add(value)
                logger.info("Inner class found ${relativePath}: ${value}")
            }
        }

        // Collect outer classes
        collectOuterClasses(classNode) { outerClassNode ->
            def outerClass = outerClassNode.replaceAll('/', '.')
            if (!importedClasses.contains(outerClass)) {
                logger.info("Outer class found ${relativePath}: ${outerClass}")

                //def classes = findClassFiles(outerClass)
                importedClasses.addAll(outerClass)
            }
        }

        if (Objects.nonNull(classNode.nestHostClass)) {
            importedClasses.add(classNode.nestHostClass.replaceAll('/', '.'))
        }

        return importedClasses.findAll { className ->
            !isExcludedClass(className) &&
                    !isExcluded(new File(className.replace('.', '/') + ".class"))
        }
    }

    protected static void collectOuterClasses(ClassNode classNode, Closure closure) {
        if (classNode.outerClass != null) {
            closure(classNode.outerClass)
        }
    }

    protected boolean isExcludedClass(String className) {
        def result = excludes.get().any { exclude ->
            className == exclude || className.startsWith(exclude)
        }
        if (result) {
            logger.debug("Excluded class: ${className}")
        }
        return result
    }

    protected boolean isExcluded(File file) {
        def path = file.toPath()
        boolean excluded = excludePatterns.get().any { pattern -> getPathMatcher("glob:$pattern").matches(path) }
        if (excluded) {
            logger.lifecycle("Excluded file: ${file.path}")
        }
        return excluded
    }

    protected boolean isExcluded(ZipEntry entry) {
        def path = FileSystems.getDefault().getPath(entry.name)
        excludePatterns.get().any { pattern -> getPathMatcher("glob:$pattern").matches(path) }
    }

    protected static PathMatcher getPathMatcher(String pattern) {
        FileSystems.getDefault().getPathMatcher(pattern)
    }

    protected void processOutputFiles(Set<ResolutionResult> resolvedFiles) {
        Set<File> jarsInClasspath = classpath.files.findAll { it.name.endsWith('.jar') }
        Set<String> usedClasses = resolvedFiles.collect { it.file.name.replace('.class', '').replace('/', '.') }

        jarsInClasspath.each { jarFile ->
            ZipFile zipFile = new ZipFile(jarFile)
            zipFile.entries().each { entry ->
                if (usedClasses.contains(entry.name.replace('.class', '').replace('/', '.'))) {
                    outputFiles.get().from(jarFile)
                    return
                }
            }
        }

        outputDirs.each { dir ->
            resolvedFiles.each { result ->
                if (result.file.exists()) {
                    def relativePath = result.relativePath
                    project.copy {
                        from result.file
                        into new File(dir, relativePath).parentFile
                    }
                } else {
                    logger.lifecycle("File does not exist: ${result.file}")
                }
            }
        }
    }
}