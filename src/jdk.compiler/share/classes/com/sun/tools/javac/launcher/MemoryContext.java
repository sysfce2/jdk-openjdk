/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.launcher;

import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.resources.LauncherProperties.Errors;

import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An object to encapsulate the set of in-memory classes, such that
 * they can be written by a file manager and subsequently used by
 * a class loader.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 */
final class MemoryContext {
    private final PrintWriter out;
    private final ProgramDescriptor descriptor;
    private final ProgramFileObject program; // "/path/to/a/b/c/Program.java" <- package a.b.c;

    private final RelevantJavacOptions options;

    private final JavacTool compiler;
    private final JavacFileManager standardFileManager;
    private final JavaFileManager memoryFileManager;

    private final Map<String, byte[]> inMemoryClasses = new HashMap<>();

    MemoryContext(PrintWriter out, Path file, RelevantJavacOptions options) throws Fault {
        this.out = out;
        this.descriptor = ProgramDescriptor.of(file);
        this.program = ProgramFileObject.of(file);
        this.options = options;

        this.compiler = JavacTool.create();
        this.standardFileManager = compiler.getStandardFileManager(null, null, null);
        try {
            List<File> searchPath = program.isFirstLineIgnored() ? List.of() : List.of(descriptor.sourceRootPath().toFile());
            standardFileManager.setLocation(StandardLocation.SOURCE_PATH, searchPath);
        } catch (IOException e) {
            throw new Error("unexpected exception from file manager", e);
        }
        this.memoryFileManager = new MemoryFileManager(inMemoryClasses, standardFileManager);
    }

    String getSourceFileAsString() {
        return program.getFile().toAbsolutePath().toString();
    }

    Set<String> getNamesOfCompiledClasses() {
        return Set.copyOf(inMemoryClasses.keySet());
    }

    /**
     * Compiles a source file, placing the class files in a map in memory.
     * Any messages generated during compilation will be written to the stream
     * provided when this object was created.
     *
     * @return the name of the first class found in the source file
     * @throws Fault if any compilation errors occur, or if no class was found
     */
    String compileProgram() throws Fault {
        var units = new ArrayList<JavaFileObject>();
        units.add(program);
        var moduleDeclaration = descriptor.sourceRootPath().resolve("module-info.java");
        if (Files.exists(moduleDeclaration)) {
            units.add(standardFileManager.getJavaFileObject(moduleDeclaration));
        }
        var opts = options.forProgramCompilation();
        var task = compiler.getTask(out, memoryFileManager, null, opts, null, units);
        var fileUri = program.toUri();
        var mainClassNameReference = new AtomicReference<String>();
        task.addTaskListener(new TaskListener() {
            @Override
            public void started(TaskEvent event) {
                if (mainClassNameReference.get() != null) return;
                if (event.getKind() != TaskEvent.Kind.ANALYZE) return;
                TypeElement element = event.getTypeElement();
                if (element.getNestingKind() != NestingKind.TOP_LEVEL) return;
                JavaFileObject source = event.getSourceFile();
                if (source == null) return;
                if (!source.toUri().equals(fileUri)) return;
                var mainClassName = element.isUnnamed()
                        ? element.getSimpleName().toString()
                        : element.getQualifiedName().toString();
                mainClassNameReference.compareAndSet(null, mainClassName);
            }
        });
        var ok = task.call();
        if (!ok) {
            throw new Fault(Errors.CompilationFailed);
        }
        if (mainClassNameReference.get() == null) {
            throw new Fault(Errors.NoClass);
        }
        return mainClassNameReference.get();
    }

    byte[] compileJavaFileByName(String name) throws Fault {
        var firstDollarSign = name.indexOf('$'); // [package . ] name [ $ enclosed ]
        var container = firstDollarSign == -1 ? name : name.substring(0, firstDollarSign);
        var path = container.replace('.', '/') + ".java";
        var file = descriptor.sourceRootPath().resolve(path);
        if (Files.notExists(file)) return null;

        var opts = options.forSubsequentCompilations();
        var unit = standardFileManager.getJavaFileObject(file);
        var task = compiler.getTask(out, memoryFileManager, null, opts, null, List.of(unit));

        var ok = task.call();
        if (!ok) {
            var fault = new Fault(Errors.CompilationFailed);
            // don't throw fault - fail fast!
            out.println(fault.getMessage());
            System.exit(2);
        }
        return inMemoryClasses.get(name);
    }

    Class<?> loadMainClass(ClassLoader parent, String mainClassName) throws ClassNotFoundException, Fault {
        var moduleInfoBytes = inMemoryClasses.get("module-info");
        if (moduleInfoBytes == null) {
            var memoryClassLoader = new MemoryClassLoader(inMemoryClasses, parent, null, descriptor, this::compileJavaFileByName);
            return Class.forName(mainClassName, true, memoryClassLoader);
        }

        var lastDotInMainClassName = mainClassName.lastIndexOf('.');
        if (lastDotInMainClassName == -1) {
            throw new Fault(Errors.UnnamedPkgNotAllowedNamedModules);
        }
        var mainClassNamePackageName = mainClassName.substring(0, lastDotInMainClassName);

        var moduleDesc = ModuleDescriptor.read(ByteBuffer.wrap(moduleInfoBytes), descriptor::computePackageNames);
        var moduleName = moduleDesc.name();

        var finder = new MemoryModuleFinder(inMemoryClasses, moduleDesc, descriptor);
        var boot = ModuleLayer.boot();
        var configuration = boot.configuration().resolve(finder, ModuleFinder.of(), Set.of(moduleName));
        var memoryClassLoader = new MemoryClassLoader(inMemoryClasses, parent, moduleDesc, descriptor, this::compileJavaFileByName);
        var controller = ModuleLayer.defineModules(configuration, List.of(boot), mn -> memoryClassLoader);
        var layer = controller.layer();

        var module = layer.findModule(moduleName).orElseThrow();
        controller.addOpens(module, mainClassNamePackageName, getClass().getModule());

        return layer.findLoader(moduleName).loadClass(mainClassName);
    }
}
