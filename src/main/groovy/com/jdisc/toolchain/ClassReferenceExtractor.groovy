//import proguard.classfile.ClassPool;
//import proguard.classfile.ClassPathEntry;
//import proguard.classfile.Clazz;
//import proguard.classfile.visitor.ClassPoolVisitor;
//import proguard.classfile.visitor.ReferencedClassVisitor;
//import proguard.io.ClassPath;
//import proguard.io.ClassPathEntry;
//import proguard.io.FileWriter;
//import proguard.io.FilteredDataEntryReader;
//import proguard.io.JarWriter;
//import proguard.io.ZipReader;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class ClassReferenceExtractor {

    public static Set<String> extractReferencedClasses(File classFile) throws IOException {
        Set<String> referencedClasses = new HashSet<>();

//        ClassPath classPath = new ClassPath();
//        classPath.addSingleClassEntry(new File("path_to_your_class_files_directory"));
//
//        ClassPool programClassPool = new ClassPool();
//        ClassPoolVisitor visitor = new ReferencedClassVisitor((clazz, referencedClass) -> {
//            referencedClasses.add(referencedClass.getName());
//        });
//
//        ZipReader zipReader = new ZipReader(new FilteredDataEntryReader(null, new ClassParser(programClassPool)));
//        zipReader.read(new File("path_to_your_class_files_directory"));

//        programClassPool.classesAccept(visitor);

        return referencedClasses;
    }
}