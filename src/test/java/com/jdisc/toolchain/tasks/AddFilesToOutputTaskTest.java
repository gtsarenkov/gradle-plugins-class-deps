package com.jdisc.toolchain.tasks;

import com.jdisc.toolchain.ResolutionResult;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;
import org.gradle.internal.impldep.org.apache.commons.lang3.stream.Streams;
import org.gradle.testfixtures.ProjectBuilder;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.moxie.ant.ClassFilter;
import org.moxie.ant.ClassUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;

public class AddFilesToOutputTaskTest {
    private final File probeFile = new File("../build-gradled/classes/java/main/com/gitblit/FederationClient.class");
    private final List<String> expectedDependencies = ClassUtil.getDependencies(probeFile);
    private Logger logger;
    private AddFilesToOutputTask addFilesToOutputTask;
    private static final File projectDir = new File(".").getAbsoluteFile();

    AddFilesToOutputTaskTest() throws IOException {
    }

    @BeforeEach
    void setup() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(projectDir)
                .withGradleUserHomeDir(new File(projectDir, "build/.gradle").getAbsoluteFile())
                .build();
        File buildDir = new File(projectDir, "../build-gradled").getAbsoluteFile();
        project.getPlugins().apply("java");
        project.getRepositories().mavenCentral();
        project.getDependencies().enforcedPlatform(project.files(new File(projectDir, "../gradle/build-bom.gradle")));
        Function<? super Object, Dependency> dependency = (dep) -> project.getDependencies().add("implementation", dep);
        Dependency[] deps = Streams.of(
                        "args4j:args4j:2.37",
                        "org.slf4j:slf4j-api:2.0.16",
                        "org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r",
                        "com.google.code.gson:gson:2.11.0",
                        "com.google.inject:guice:7.0.0",
                        "commons-io:commons-io:2.17.0",
                        "org.apache.lucene:lucene-analysis-common:10.0.0",
                        "org.apache.lucene:lucene-queryparser:10.0.0",
                        "org.apache.lucene:lucene-highlighter:10.0.0",
                        "ro.fortsoft.pf4j:pf4j:0.9.0",
                        "org.apache.commons:commons-lang3:3.17.0",
                        "org.apache.wicket:wicket:1.4+",
                        "org.apache.wicket:wicket-auth-roles:1.4+",
                        "org.apache.wicket:wicket-extensions:1.4+",
                        "org.pegdown:pegdown:1.5.0",
                        "org.fusesource.wikitext:confluence-core:1.4",
                        "org.fusesource.wikitext:mediawiki-core:1.4",
                        "org.fusesource.wikitext:textile-core:1.4",
                        "org.fusesource.wikitext:tracwiki-core:1.4",
                        "org.fusesource.wikitext:twiki-core:1.4",
                        "org.jsoup:jsoup:1.18.1",
                        "org.apache.tika:tika-core:3.0.0",
                        "com.google.inject.extensions:guice-servlet:7.0.0",
                        "org.bouncycastle:bcprov-jdk18on:1.79",
                        "org.bouncycastle:bcmail-jdk18on:1.79",
                        "org.bouncycastle:bcpkix-jdk18on:1.79",
                        "freemarker:freemarker:2.3.9",
                        "org.apache.sshd:sshd-core:1.7.0",
                        "org.apache.commons:commons-compress:1.27.1",
                        "org.eclipse.jgit:org.eclipse.jgit.http.server:6.10.0.202406032230-r",
                        "com.github.dblock.waffle:waffle-jna:1.8.1",
                        "org.apache.mina:mina-core:2.0.25",
                        "org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.10.0.202406032230-r",
                        "com.unboundid:unboundid-ldapsdk:7.0.1",
                        "org.kohsuke:libpam4j:1.10",
                        "com.force.api:force-partner-api:24.0.0",
                        project.files(new File(buildDir, "classes/java/main").getAbsoluteFile()))
                .map(dependency).toArray(Dependency[]::new);
        project.getLayout().getBuildDirectory().set(buildDir);
        Set<File> resolved = project.getConfigurations().detachedConfiguration(deps).resolve();
        addFilesToOutputTask = project.getTasks().create("addFilesToOutputTask", AddFilesToOutputTask.class);
        addFilesToOutputTask.getClasspath().set(project.files(resolved));
        logger = addFilesToOutputTask.getLogger();
        resolved.forEach(component -> logger.debug("Resolved class path Jars {}", component.getAbsolutePath()));
        //logger.lifecycle("DDDDDDDDDDDDDD {} {}", buildDir.toPath(), addFilesToOutputTask.getClasspath().getAsPath());
        logger.info("Main Class File {}:{}", probeFile.toPath().normalize().toAbsolutePath(), probeFile.exists());
    }

    @Test
    void findImportedClasses() {
        org.apache.tools.ant.Project mockAntProject = Mockito.mock(org.apache.tools.ant.Project.class);
        Mockito.doAnswer(logOnAntProject()).when(mockAntProject).log(Mockito.anyString(), Mockito.anyInt());
        ClassFilter classFilter = new ClassFilter(new org.moxie.ant.Logger(mockAntProject));
        Set<String> expectedDependencies = this.expectedDependencies.stream().filter(classFilter::include).map(s -> s.replaceAll("^\\[L(.*?);?$", "$1").replace("/", ".")).collect(Collectors.toSet());
        expectedDependencies.add("com.gitblit.FederationClient"); // This is known difference.
        Set<String> importedClasses = addFilesToOutputTask.findImportedClasses(probeFile);
        // This classes found during recursion
        importedClasses.addAll(Set.of("com.gitblit.Keys", "com.gitblit.utils.XssFilter"));
        MatcherAssert.assertThat(importedClasses, containsInAnyOrder(expectedDependencies.toArray()));
    }

    private Answer<Void> logOnAntProject() {
        return invocationOnMock -> {
            logger.info("Ant-Moxie: {}", invocationOnMock.getArguments()[0]);
            return null;
        };
    }

    @Test
    void resolveImportedClasses() {
        org.apache.tools.ant.Project mockAntProject = Mockito.mock(org.apache.tools.ant.Project.class);
        Mockito.doAnswer(logOnAntProject()).when(mockAntProject).log(Mockito.anyString(), Mockito.anyInt());
        ClassFilter classFilter = new ClassFilter(new org.moxie.ant.Logger(mockAntProject));
        Set<String> dependencies = expectedDependencies.stream().filter(classFilter::include).map(s -> s.replaceAll("^\\[L(.*?);?$", "$1").replace("/", ".")).filter(name -> name.startsWith("com.gitblit")).collect(Collectors.toSet());
        dependencies.add("com.gitblit.FederationClient"); // This is known difference.
        Set<ResolutionResult> importedClassesResult = addFilesToOutputTask.resolveImportedClasses(new TreeSet<>(Set.of(new ResolutionResult("com.gitblit.FederationClient", new File("../build-gradled"), probeFile, "com/gitblit/FederationClient"))));
        Set<String> importedClasses = importedClassesResult.stream().map(ResolutionResult::getCanonicalClassName).collect(Collectors.toSet());
//        if (!importedClasses.equals(dependencies)) {
//            Set<String> union = new TreeSet(importedClasses);
//            union.addAll(dependencies);
//            Set<String> intersection = new TreeSet(importedClasses);
//            intersection.retainAll(dependencies);
//            union.removeAll(intersection);
//            union.each { dd -> logger.error("error difference ${project.buildDir.toPath().relativize(result.file.toPath())} ${dd} new=${importedClasses.contains(dd)} old=${dependencies.contains(dd)}") }
//        }
        MatcherAssert.assertThat(importedClasses, Matchers.hasItems(dependencies.toArray(String[]::new)));
    }
}