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

package org.parchmentmc.librarian.neogradle;

import net.neoforged.gradle.common.runtime.definition.IDelegatingRuntimeDefinition;
import net.neoforged.gradle.common.runtime.naming.tasks.ApplyMappingsToSourceJar;
import net.neoforged.gradle.common.util.MappingUtils;
import net.neoforged.gradle.dsl.common.extensions.Mappings;
import net.neoforged.gradle.dsl.common.extensions.Minecraft;
import net.neoforged.gradle.dsl.common.runtime.naming.NamingChannel;
import net.neoforged.gradle.dsl.common.runtime.naming.TaskBuildingContext;
import net.neoforged.gradle.dsl.common.runtime.tasks.Runtime;
import net.neoforged.gradle.dsl.common.tasks.WithOutput;
import net.neoforged.gradle.dsl.common.util.NamingConstants;
import net.neoforged.gradle.mcp.naming.renamer.McpSourceRenamer;
import net.neoforged.gradle.mcp.runtime.definition.McpRuntimeDefinition;
import net.neoforged.gradle.mcp.runtime.tasks.DownloadArtifact;
import net.neoforged.gradle.util.TransformerUtils;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Property;
import org.gradle.api.reflect.TypeOf;
import org.gradle.api.tasks.TaskProvider;
import org.parchmentmc.librarian.neogradle.tasks.CreateParchmentCSVZip;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ParchmentNamingChannelConfigurator {

    private static final ParchmentNamingChannelConfigurator instance = new ParchmentNamingChannelConfigurator();

    public static ParchmentNamingChannelConfigurator getInstance() {
        return instance;
    }

    private ParchmentNamingChannelConfigurator() {
    }

    public void apply(final Project project) {
        final Minecraft minecraftExtension = project.getExtensions().getByType(Minecraft.class);

        final Mappings mappingsExtension = minecraftExtension.getMappings();
        final Property<Boolean> hasAcceptedLicenseProperty = project.getObjects().property(Boolean.class);
        mappingsExtension.getExtensions().add(TypeOf.typeOf(Property.class), "acceptParchmentLicense", hasAcceptedLicenseProperty);
        hasAcceptedLicenseProperty.convention(false);

        NamedDomainObjectProvider<NamingChannel> officialChannel = minecraftExtension.getNamingChannels().named("official");

        minecraftExtension.getNamingChannels().register("parchment", namingChannelProvider -> {
            namingChannelProvider.getMinecraftVersionExtractor().set(this::extractMinecraftVersion);
            namingChannelProvider.getApplySourceMappingsTaskBuilder().set(c -> this.buildApplySourceMappingTask(c, officialChannel.get()));
            namingChannelProvider.getApplyCompiledMappingsTaskBuilder().set(officialChannel.flatMap(NamingChannel::getApplyCompiledMappingsTaskBuilder));
            namingChannelProvider.getUnapplyCompiledMappingsTaskBuilder().set(officialChannel.flatMap(NamingChannel::getUnapplyCompiledMappingsTaskBuilder));
            namingChannelProvider.getUnapplyAccessTransformerMappingsTaskBuilder().set(officialChannel.flatMap(NamingChannel::getUnapplyAccessTransformerMappingsTaskBuilder));
            namingChannelProvider.getGenerateDebuggingMappingsJarTaskBuilder().set(officialChannel.flatMap(NamingChannel::getGenerateDebuggingMappingsJarTaskBuilder));
            namingChannelProvider.getHasAcceptedLicense().convention(hasAcceptedLicenseProperty);
            namingChannelProvider.getLicenseText().set(getLicenseText());
        });

    }

    private String extractMinecraftVersion(Map<String, String> stringStringMap) {
        final String hardcodedMcVersion = MappingUtils.getMinecraftVersion(stringStringMap);
        if (hardcodedMcVersion != null)
            return hardcodedMcVersion;

        final ParchmentMappingVersion version = ParchmentMappingVersion.of(stringStringMap.get("version"));

        return version.mcVersion();
    }

    private String getLicenseText() {
        return "Parchment is published under CC0. Please familiarise yourself with the license found here: https://creativecommons.org/share-your-work/public-domain/cc0/";
    }

    private TaskProvider<? extends Runtime> buildApplySourceMappingTask(TaskBuildingContext context, final NamingChannel mcpNamingChannel) {
        Optional<McpRuntimeDefinition> runtimeDefinition = context.getRuntimeDefinition()
                .filter(McpRuntimeDefinition.class::isInstance)
                .map(McpRuntimeDefinition.class::cast);

        if (!runtimeDefinition.isPresent()) {
            //Resolve delegation
            runtimeDefinition = context.getRuntimeDefinition()
                    .filter(IDelegatingRuntimeDefinition.class::isInstance)
                    .map(IDelegatingRuntimeDefinition.class::cast)
                    .map(IDelegatingRuntimeDefinition::getDelegate)
                    .filter(McpRuntimeDefinition.class::isInstance)
                    .map(McpRuntimeDefinition.class::cast);
        }

        if (!runtimeDefinition.isPresent()) {
            throw new IllegalStateException("The runtime definition is not present.");
        }

        String requestedVersion = context.getMappingVersion().get(NamingConstants.Version.VERSION);
        if (!requestedVersion.contains("-")) {
            //We do not have an MC version specific ParchmentVersions.
            requestedVersion = requestedVersion + "-" + runtimeDefinition.get().getSpecification().getMinecraftVersion();
        }
        final ParchmentMappingVersion version = ParchmentMappingVersion.of(requestedVersion);

        final TaskProvider<? extends Runtime> downloadParchment = context.getProject().getTasks().register(
                context.getTaskNameBuilder().apply("provideParchment" + version.asTaskIdentity()),
                DownloadArtifact.class,
                task -> {
                    task.getArtifactCoordinate().set(version.asArtifactCoordinate());
                }
        );

        context.addTask(downloadParchment);

        final TaskProvider<? extends Runtime> applySourceMappingsTask = mcpNamingChannel.getApplySourceMappingsTaskBuilder().get().build(context);

        final McpRuntimeDefinition mcpRuntimeDefinition = runtimeDefinition.get();
        final String mappingsFilePath = mcpRuntimeDefinition.getMcpConfig().getData("mappings");
        final File mappingsFile = new File(mcpRuntimeDefinition.getUnpackedMcpZipDirectory(), Objects.requireNonNull(mappingsFilePath));

        final File constructorsDataFile = mcpRuntimeDefinition.getMcpConfig().isOfficial() ? null :
                new File(mcpRuntimeDefinition.getUnpackedMcpZipDirectory(), Objects.requireNonNull(mcpRuntimeDefinition.getMcpConfig().getData("constructors")));


        final TaskProvider<? extends Runtime> createParchmentZipTask = context.getProject().getTasks().register(
                context.getTaskNameBuilder().apply("createParchment" + version.asTaskIdentity()),
                CreateParchmentCSVZip.class,
                task -> {
                    task.getClientMappings().set(context.getClientMappings().flatMap(WithOutput::getOutput));
                    task.getMcpMappings().fileValue(mappingsFile);
                    task.getIsOfficial().set(mcpRuntimeDefinition.getMcpConfig().isOfficial());
                    task.getConstructorsData().fileValue(constructorsDataFile);
                    task.getParchment().set(downloadParchment.flatMap(WithOutput::getOutput));

                    task.dependsOn(context.getClientMappings());
                    task.dependsOn(downloadParchment);
                }
        );

        context.addTask(createParchmentZipTask);

        applySourceMappingsTask.configure(task -> {
            if (task instanceof ApplyMappingsToSourceJar) {
                final ApplyMappingsToSourceJar applyMappingsToSourceJar = (ApplyMappingsToSourceJar) task;
                applyMappingsToSourceJar.getSourceRenamer().set(
                        createParchmentZipTask.flatMap(WithOutput::getOutput)
                                .map(RegularFile::getAsFile)
                                .map(TransformerUtils.guard(McpSourceRenamer::from))
                );

                applyMappingsToSourceJar.dependsOn(createParchmentZipTask);
            }
        });

        return applySourceMappingsTask;
    }
}
