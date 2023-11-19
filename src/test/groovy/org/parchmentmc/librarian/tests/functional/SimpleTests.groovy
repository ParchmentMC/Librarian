/*
 * Librarian
 * Copyright (C) 2021 ParchmentMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.parchmentmc.librarian.tests.functional;

import net.neoforged.trainingwheels.gradle.functional.BuilderBasedTestSpecification;

class SimpleTests extends BuilderBasedTestSpecification {

    def "supports creating a default project"() {
        given:
        def obfuscatedProject = create("obfuscated", builder -> {
            builder.build("""
            plugins {
                id 'org.parchmentmc.librarian.neogradle'
            }
            
            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(17)
                }
            }
            
            minecraft {
                mappings {
                    channel = parchment()
                    version '2022.11.27'
                }
            }
                        
            dependencies {
                implementation 'net.minecraftforge:forge:1.19.2-43.1.34'
            }
            """).plugin('java').withToolchains();
        })

        when:
        def result = obfuscatedProject.run { it ->
            it.tasks(':build')
            it.arguments('--stacktrace')
        }

        then:
        result.output.contains('BUILD SUCCESSFUL')
    }

}