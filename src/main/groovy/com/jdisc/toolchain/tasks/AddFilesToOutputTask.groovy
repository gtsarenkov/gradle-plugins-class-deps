package com.jdisc.toolchain.tasks

import com.jdisc.toolchain.ResolutionResult
import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

import java.nio.file.*
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

            "module-info",

            "boolean",
            "byte",
            "char",
            "short",
            "int",
            "long",
            "float",
            "double"
    ))

    @Classpath
    final Property<FileCollection> classpath = project.objects.property(FileCollection.class).convention(project.objects.fileCollection())

    @Input
    final ListProperty<String> excludePatterns = project.objects.listProperty(String)

    @Input
    final Property<Boolean> ignoreMissingClassFile = project.objects.property(Boolean.class).convention(false)

    @Input
    final Property<Boolean> analyzeJarClasses = project.objects.property(Boolean).convention(true)

    @Optional
    @OutputFile
    final RegularFileProperty cacheFileName = project.objects.fileProperty()

    @OutputDirectories
    final ConfigurableFileCollection outputDirs = project.objects.fileCollection()

    @Internal
    final ConfigurableFileCollection outputJars = project.objects.fileCollection()

    private Comparator<File> classpathSortComparator = (File f1, File f2) -> {
        if ((f1.directory || !f1.name.endsWith(".jar")) && (!f2.directory || f1.name.endsWith(".jar"))) {
            return 1
        } else if ((!f1.directory || f1.name.endsWith(".jar")) && (f2.directory || !f1.name.endsWith(".jar"))) {
            return -1
        } else {
            return f1.name <=> f2.name
        }
    }

    TreeSet<File> sortedClasspathFileTree() {
        return new TreeSet<>(classpathSortComparator)
    }

    static Boolean isAcceptableClasspathEntry(File file) {
        return file.exists() && file.directory || file.name.endsWith(".jar")
    }

    @TaskAction
    void addFiles() {
        if (!classpath.present || classpath.get().isEmpty()) {
            throw new GradleException("Lookup classpath for task ${getName()} must be non-empty")
        }
        Set<File> missingClasspath = sortedClasspathFileTree()
        missingClasspath.addAll(classpath.get().files.findAll { !isAcceptableClasspathEntry(it) })
        if (!missingClasspath.empty) {
            missingClasspath.each {
                logger.error(MessageFormat.format("Non-existing or invalid classpath entry detected: {0}", it.absolutePath))
            }
            throw new GradleException(MessageFormat.format("Non-existing or invalid classpath entry detected: {0}", missingClasspath.findAll { true }.absolutePath))
        }

        Map<String, File> classMap = buildClassMap()
        if (cacheFileName.present) {
            saveClassMapToFile(classMap, cacheFileName.get().asFile)
        }

        Set<File> trackedJars = [] as Set<File>
        Set<ResolutionResult> classFileData = findClassFiles([mainClass.get()] + this.classes.get(), trackedJars, classMap)
        Set<ResolutionResult> resolvedFiles = resolveImportedClasses(classFileData, trackedJars, classMap)

        processOutputFiles(resolvedFiles)

        logger.lifecycle("Found ${outputJars.collect {it.size() }.sum()} bytes in jar artifacts")

        outputJars.each {
            logger.info "----- Artifact Dependency Jar ${it}"
        }
    }

    protected Set<ResolutionResult> findClassFiles(Collection<String> classNames, Set<File> trackedJars, Map<String, File> classMap) {
        Set<ResolutionResult> classFileData = new HashSet<>()
        Set<File> jarFiles = sortedClasspathFileTree()
        def regex = /^(\[L)|(;$)|(\[]$)/

        classNames.each { classNameMangled ->
            def className = classNameMangled.replaceAll(regex, "")
            boolean notFound = true

            if (classMap.containsKey(className)) {
                File file = classMap.get(className)
                if (file.directory) {
                    File potentialFile = new File(file, className.replace('.', '/') + ".class")
                    if (potentialFile.exists() && !isExcluded(potentialFile)) {
                        classFileData.add(new ResolutionResult(className, file, potentialFile, potentialFile.path - file.path))
                        notFound = false
                    }
                } else if (file.name.endsWith(".jar")) {
                    try (ZipFile zipFile = new ZipFile(file)) {
                        ZipEntry zipEntry = zipFile.getEntry(className.replace('.', '/') + ".class")
                        if (zipEntry != null && !isExcluded(zipEntry)) {
                            if (analyzeJarClasses.get()) {
                                logger.debug("Found class ${className} in jar ${file.absolute}")
                                classFileData.add(new ResolutionResult(className, file, zipEntry, zipEntry.name))
                            }
                            jarFiles.add(file)
                            notFound = false
                        }
                    }
                }
            }

            if (notFound && !isExcludedClass(className)) {
                handleMissingClassFile(className, classNameMangled)
            }
        }

        trackedJars.addAll(jarFiles)
        classFileData.removeAll { result -> trackedJars.contains(result.file) }

        return classFileData
    }

    protected void handleMissingClassFile(String className, String originClassName) {
        if (ignoreMissingClassFile) {
            logger.warn(MessageFormat.format("Class file {0} cannot be resolved from {1}", className, originClassName))
        } else {
            logger.error(MessageFormat.format("Class file {0} cannot be resolved from {1}", className, originClassName))
            throw new GradleException(MessageFormat.format("Class file {0} cannot be resolved from {1}", className, originClassName))
        }
    }

    protected Set<ResolutionResult> resolveImportedClasses(Set<ResolutionResult> classFileData, Set<File> trackedJars, Map<String, File> classMap) {
        Set<ResolutionResult> resolvedFiles = new TreeSet()
        Set<ResolutionResult> newClasses = new TreeSet(classFileData)

        while (!newClasses.isEmpty()) {
            Set<ResolutionResult> currentClassFiles = new HashSet<>(newClasses)
            newClasses.clear()

            currentClassFiles.each { result ->
                if (!resolvedFiles.contains(result)) {
                    resolvedFiles.add(result)

                    URL url = result.file.toURL();

                    Set<String> importedClasses
                    try (InputStream is = url.openStream()) {
                        importedClasses = findImportedClasses(is)
                    }
                    Set<ResolutionResult> resolvedImportedFiles = findClassFiles(importedClasses, trackedJars, classMap)

                    newClasses.addAll(resolvedImportedFiles.findAll { !resolvedFiles.contains(it) })
                }
            }
        }

        return resolvedFiles
    }

    protected Set<String> findImportedClasses(InputStream classFile) {
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
                    MethodInsnNode methodInsnNode = (MethodInsnNode) instruction
                    // Use ASM Type to parse parameter types from the descriptor
                    Type[] argumentTypes = Type.getArgumentTypes(methodInsnNode.desc)

                    // Collect each argument type's class name
                    argumentTypes.each { argType ->
                        String className = argType.className
                        importedClasses.add(className)
                    }
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

        classNode.interfaces.each { intfName ->
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

// TODO:                def classes = findClassFiles(outerClass)
                importedClasses.addAll(outerClass)
            }
        }

        if (Objects.nonNull(classNode.nestHostClass)) {
            importedClasses.add(classNode.nestHostClass.replaceAll('/', '.'))
        }

        return importedClasses.findAll { className ->
            !isExcludedClass(className.toString()) &&
                    !isExcluded(new File(className.toString().replace('.', '/') + ".class"))
        } as Set<String>
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
        Set<String> usedClasses = resolvedFiles.collect { it.canonicalClassName }

        logger.debug("----- Used classes found ${usedClasses.join(",")}")

//        Set<File> jarsInClasspath = classpath.get().files.findAll { it.name.endsWith('.jar') }.findAll { jarFile ->
//            def usedJar
//            try (ZipFile zipFile = new ZipFile(jarFile)) {
//                usedJar = zipFile.entries().any { entry -> usedClasses.contains(entry.name.replace('.class', '').replace('/', '.')) }
//            }
//            usedJar
//        }

        //jarsInClasspath.each { outputJars.from(it) }
        def dd = new TreeSet(classpathSortComparator)
        dd.addAll(resolvedFiles.findAll { !it.baseFile.directory }.collect { it.baseFile })
        outputJars.from(dd)

        outputDirs.each { dir ->
            resolvedFiles.each { result ->
                def classExists = result.file.getScheme().equals("file") && Files.exists(Paths.get(result.file))
                if (classExists) {
                    def relativePath = result.relativePath

                    project.copy {
                        from result.file
                        into new File(dir, relativePath).parentFile
                    }
                } else if (!doesFileExist(result.file)) {
                    logger.warn("File does not exist: ${result.file}")
                }
            }
        }
    }

    static boolean doesFileExist(URI uri) throws IOException, URISyntaxException {
        if (uri.getScheme().equals("jar")) {
            // Handle jar:file URI
            try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                String[] parts = uri.toString().split("!/");
                if (parts.length == 2) {
                    Path pathInJar = fs.getPath(parts[1]);
                    return Files.exists(pathInJar);
                }
            }
        } else if (uri.getScheme().equals("file")) {
            // Handle file URI
            Path path = Paths.get(uri);
            return Files.exists(path);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri.getScheme());
        }
        return false;
    }

    /**
     * Builds a map of canonical class names to classpath entries.
     * @return a Map of canonical class names to the classpath entry (File)
     */
    private Map<String, File> buildClassMap() {
        Map<String, File> classMap = [:]

        classpath.get().each { File entry ->
            if (entry.directory) {
                entry.eachFileRecurse { File file ->
                    if (file.name.endsWith('.class')) {
                        String className = getClassNameFromFile(entry, file)
                        classMap[className] = entry
                    }
                }
            } else if (entry.name.endsWith('.jar')) {
                try (ZipFile zipFile = new ZipFile(entry)) {
                    zipFile.entries().each { zipEntry ->
                        if (zipEntry.name.endsWith('.class')) {
                            String className = getClassNameFromZipEntry(zipEntry.name)
                            classMap[className] = entry
                        }
                    }
                }
            }
        }

        return classMap
    }

    /**
     * Derives the canonical class name from a .class file within a directory.
     * @param rootDir the root directory of the classpath entry
     * @param classFile the .class file
     * @return the canonical class name
     */
    protected static String getClassNameFromFile(File rootDir, File classFile) {
        String relativePath = classFile.absolutePath - rootDir.absolutePath - File.separator
        return relativePath.replace(File.separator, '.').replaceAll(/\.class$/, '')
    }

    /**
     * Derives the canonical class name from a .class file within a ZIP (JAR) entry.
     * @param entryName the name of the ZIP entry
     * @return the canonical class name
     */
    protected static String getClassNameFromZipEntry(String entryName) {
        return entryName.replace('/', '.').replaceAll(/\.class$/, '')
    }

    /**
     * Saves the classMap to a file in JSON format.
     * @param classMap the map of class names to classpath entries
     * @param file the file to save the map to
     */
    protected static void saveClassMapToFile(Map<String, File> classMap, File file) {
        // Convert the map to a JSON-like string representation
        String jsonContent = JsonOutput.toJson(classMap.collectEntries { key, value -> [key, value.absolutePath] })

        // Write the JSON content to the file
        file.text = JsonOutput.prettyPrint(jsonContent)
    }
}