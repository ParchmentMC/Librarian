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

package org.parchmentmc.librarian.forgegradle;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import net.minecraftforge.gradle.common.config.MCPConfigV2;
import net.minecraftforge.gradle.common.util.HashFunction;
import net.minecraftforge.gradle.common.util.HashStore;
import net.minecraftforge.gradle.common.util.MavenArtifactDownloader;
import net.minecraftforge.gradle.common.util.Utils;
import net.minecraftforge.gradle.mcp.ChannelProvider;
import net.minecraftforge.gradle.mcp.MCPRepo;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;
import net.minecraftforge.srgutils.IMappingFile.INode;
import net.minecraftforge.srgutils.IMappingFile.IPackage;
import net.minecraftforge.srgutils.IMappingFile.IParameter;
import net.minecraftforge.srgutils.IRenamer;
import org.gradle.api.Project;
import org.parchmentmc.feather.io.gson.MDCGsonAdapterFactory;
import org.parchmentmc.feather.io.gson.NamedAdapter;
import org.parchmentmc.feather.io.gson.OffsetDateTimeAdapter;
import org.parchmentmc.feather.io.gson.SimpleVersionAdapter;
import org.parchmentmc.feather.io.gson.metadata.MetadataAdapterFactory;
import org.parchmentmc.feather.mapping.MappingDataContainer.ClassData;
import org.parchmentmc.feather.mapping.MappingDataContainer.FieldData;
import org.parchmentmc.feather.mapping.MappingDataContainer.MethodData;
import org.parchmentmc.feather.mapping.MappingDataContainer.PackageData;
import org.parchmentmc.feather.mapping.MappingDataContainer.ParameterData;
import org.parchmentmc.feather.mapping.VersionedMappingDataContainer;
import org.parchmentmc.feather.named.Named;
import org.parchmentmc.feather.util.SimpleVersion;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ParchmentChannelProvider implements ChannelProvider {
    protected static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(new MDCGsonAdapterFactory())
            .registerTypeAdapter(SimpleVersion.class, new SimpleVersionAdapter())
            .registerTypeAdapterFactory(new MetadataAdapterFactory())
            .registerTypeAdapter(Named.class, new NamedAdapter())
            .registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeAdapter())
            .create();
    protected static final Pattern PARCHMENT_PATTERN = Pattern.compile("(?<mappingsversion>[\\w\\-.]+)-(?<mcpversion>(?<mcversion>[\\d.]+)(?:-\\d{8}\\.\\d{6})?)");
    protected static final Pattern LETTERS_ONLY_PATTERN = Pattern.compile("[a-zA-Z]+");

    @Nonnull
    @Override
    public Set<String> getChannels() {
        return ImmutableSet.of("parchment");
    }

    @Nullable
    @Override
    public File getMappingsFile(MCPRepo mcpRepo, Project project, String channel, String version) throws IOException {
        // Format is {MAPPINGS_VERSION}-{MC_VERSION}-{MCP_VERSION} where MCP_VERSION is optional
        Matcher matcher = PARCHMENT_PATTERN.matcher(version);
        if (!matcher.matches())
            throw new IllegalStateException("Parchment version of " + version + " is invalid");

        String mappingsversion = matcher.group("mappingsversion");
        String mcversion = matcher.group("mcversion");
        String mcpversion = matcher.group("mcpversion");

        File client = MavenArtifactDownloader.generate(project, "net.minecraft:client:" + mcversion + ":mappings@txt", true);
        if (client == null)
            throw new IllegalStateException("Could not create " + mcversion + " official mappings due to missing ProGuard mappings.");

        File mcp = getMCP(project, mcpversion);
        if (mcp == null)
            return null;

        MCPConfigV2 config = MCPConfigV2.getFromArchive(mcp);

        IMappingFile srg = findObfToSrg(mcp, config);
        if (srg == null)
            throw new IllegalStateException("Could not create " + mcpversion + " parchment mappings due to missing MCP's tsrg");

        File dep = getParchmentZip(project, mappingsversion, mcversion);

        File mappings = cacheParchment(project, mcpversion, mappingsversion, "zip");
        HashStore cache = new HashStore()
                .load(cacheParchment(project, mcpversion, mappingsversion, "zip.input"))
                .add("mcp", mcp)
                .add("mcversion", version)
                .add("mappings", dep)
                .add("codever", "1");

        if (cache.isSame() && mappings.exists())
            return mappings;

        VersionedMappingDataContainer mappingData;

        try (ZipFile zip = new ZipFile(dep)) {
            ZipEntry entry = zip.getEntry("parchment.json");
            if (entry == null)
                throw new IllegalStateException("Parchment zip did not contain \"parchment.json\"");

            mappingData = GSON.fromJson(new InputStreamReader(zip.getInputStream(entry)), VersionedMappingDataContainer.class);
        }

        IMappingFile mojToObf = IMappingFile.load(client);
        // Have to do it this way to preserve parameters and eliminate SRG classnames
        IMappingFile mojToSrg = srg.reverse().chain(mojToObf.reverse()).reverse().rename(new IRenamer() {
            @Override
            public String rename(IClass value) {
                return value.getOriginal();
            }
        });

        // All the CSV data holders
        String[] header = {"searge", "name", "desc"};
        List<String[]> packages = Lists.<String[]>newArrayList(header);
        List<String[]> classes = Lists.<String[]>newArrayList(header);
        List<String[]> fields = Lists.<String[]>newArrayList(header);
        List<String[]> methods = Lists.<String[]>newArrayList(header);
        List<String[]> parameters = Lists.<String[]>newArrayList(header);

        mojToSrg.getPackages().forEach(srgPackage -> {
            PackageData packageData = mappingData.getPackage(srgPackage.getOriginal());
            populateMappings(packages, null, srgPackage, packageData != null ? packageData.getJavadoc() : null);
        });

        mojToSrg.getClasses().forEach(srgClass -> {
            ClassData classData = mappingData.getClass(srgClass.getOriginal());
            populateMappings(classes, srgClass, srgClass, classData != null ? classData.getJavadoc() : null);

            srgClass.getFields().forEach(srgField -> {
                FieldData fieldData = classData != null ? classData.getField(srgField.getOriginal()) : null;
                populateMappings(fields, srgClass, srgField, fieldData != null ? fieldData.getJavadoc() : null);
            });

            srgClass.getMethods().forEach(srgMethod -> {
                MethodData methodData = classData != null ? classData.getMethod(srgMethod.getOriginal(), srgMethod.getDescriptor()) : null;
                StringBuilder mdJavadoc = methodData != null ? new StringBuilder(getJavadocs(methodData.getJavadoc())) : new StringBuilder();
                populateParameters(config.isOfficial(), parameters, srgMethod, methodData, mdJavadoc);
                populateMappings(methods, srgClass, srgMethod, mdJavadoc.toString());
            });
        });

        if (!mappings.getParentFile().exists())
            mappings.getParentFile().mkdirs();

        try (FileSystem zipFs = FileSystems.newFileSystem(new URI("jar:" + mappings.toURI()), ImmutableMap.of("create", "true"))) {
            Path rootPath = zipFs.getPath("/");
            writeCsv("classes.csv", classes, rootPath);
            writeCsv("fields.csv", fields, rootPath);
            writeCsv("methods.csv", methods, rootPath);
            writeCsv("params.csv", parameters, rootPath);
            writeCsv("packages.csv", packages, rootPath);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        cache.save();
        Utils.updateHash(mappings, HashFunction.SHA1);

        return mappings;
    }

    protected static void populateParameters(boolean isOfficialExport, List<String[]> parameters, IMethod srgMethod, MethodData methodData, StringBuilder mdJavadoc) {
        if (methodData == null || methodData.getParameters().isEmpty())
            return;

        List<IParameter> srgParams = new ArrayList<>(srgMethod.getParameters());
        List<ParameterData> methodParams = new ArrayList<>(methodData.getParameters());
        // If the MCPConfig export is official (1.17+) and the param # doesn't match, we need to skip it
        // This is because on non-official exports, the parameter SRG id can be reconstructed from method SRG name and parameter JVM index.
        // This is not possible on official exports with the newer parameter SRG format.
        if (isOfficialExport && methodParams.size() != srgParams.size())
            return;

        boolean isConstructor = methodData.getName().equals("<init>");
        for (int i = 0; i < methodParams.size(); i++) {
            ParameterData parameter = methodParams.get(i);
            String srgParam;
            // official export == 1.17+
            if (isOfficialExport) {
                srgParam = srgParams.get(i).getMapped();
            } else {
                String srgId = srgMethod.getMapped().indexOf('_') == -1
                        ? srgMethod.getMapped()
                        : srgMethod.getMapped().split("_")[1];
                if (LETTERS_ONLY_PATTERN.matcher(srgId).matches())
                    continue; // This means it's a mapped parameter of a functional interface method and we can't use it.
                srgParam = String.format(isConstructor ? "p_i%s_%d_" : "p_%s_%d_", srgId, parameter.getIndex());
            }
            String paramName = parameter.getName();
            if (paramName != null)
                parameters.add(new String[]{srgParam, paramName, ""});
            String paramJavadoc = getJavadocs(parameter);
            if (!paramJavadoc.isEmpty())
                mdJavadoc.append("\\n@param ").append(paramName != null ? paramName : srgParam).append(' ').append(paramJavadoc);
        }
    }

    protected static File getParchmentZip(Project project, String mappingsversion, String mcversion) {
        String artifact = "org.parchmentmc.data:parchment-" + mcversion + ":" + mappingsversion + ":checked@zip";
        File dep = MavenArtifactDownloader.manual(project, artifact, false); // currently breaks - getDependency(project, artifact);
        if (dep == null) {
            // TODO remove this later? or keep backwards-compatibility with older releases?
            dep = MavenArtifactDownloader.manual(project, artifact.replace(":checked", ""), false);
        }
        if (dep == null)
            throw new IllegalStateException("Could not find Parchment version of " + mappingsversion + '-' + mcversion + " with artifact " + artifact);
        return dep;
    }

    // private static Map<Object, File> dependencyCache = new ConcurrentHashMap<>();
    // protected static synchronized File getDependency(Project project, Object dependencyNotation) {
    //     File cached = dependencyCache.get(dependencyNotation);
    //     if (cached != null)
    //         return cached;
    //     try {
    //         Iterator<File> iterator = project.getConfigurations().detachedConfiguration(project.getDependencies().create(dependencyNotation)).resolve().iterator();
    //         File dependency = iterator.hasNext() ? iterator.next() : null;
    //         if (dependency != null)
    //             dependencyCache.put(dependencyNotation, dependency);
    //         return dependency;
    //     } catch (ResolveException e) {
    //         return null;
    //     }
    // }

    @Nonnull
    protected static Path getCacheBase(Project project) {
        File gradleUserHomeDir = project.getGradle().getGradleUserHomeDir();
        return Paths.get(gradleUserHomeDir.getPath(), "caches", "parchmentgradle");
    }

    @Nonnull
    protected static File getCache(Project project, String... tail) {
        return Paths.get(getCacheBase(project).toString(), tail).toFile();
    }

    @Nonnull
    protected static File cacheParchment(Project project, String mcpversion, String mappingsVersion, String ext) {
        return getCache(project, "org", "parchmentmc", "data", "parchment-" + mcpversion, mappingsVersion, "parchment-" + mcpversion + '-' + mappingsVersion + '.' + ext);
    }

    protected static void populateMappings(List<String[]> mappings, IClass srgClass, INode srgNode, Object javadoc) {
        String desc = getJavadocs(javadoc);
        if (srgNode instanceof IPackage || srgNode instanceof IClass) {
            String name = srgNode.getMapped().replace('/', '.');
            // TODO fix InstallerTools so that we don't have to expand the csv size for no reason
            if (!desc.isEmpty())
                mappings.add(new String[]{name, name, desc});
            return;
        }
        String srgName = srgNode.getMapped();
        String mojName = srgNode.getOriginal();
        boolean isSrg = srgName.startsWith("p_") || srgName.startsWith("func_") || srgName.startsWith("m_") || srgName.startsWith("field_") || srgName.startsWith("f_");
        // If it's not a srg id and has javadocs, we need to add the class to the beginning as it is a special method/field of some kind
        if (!isSrg && !desc.isEmpty() && (srgNode instanceof IMethod || srgNode instanceof IField)) {
            srgName = srgClass.getMapped().replace('/', '.') + '#' + srgName;
        }
        // Only add to the mappings list if it is mapped or has javadocs
        if ((isSrg && !srgName.equals(mojName)) || !desc.isEmpty())
            mappings.add(new String[]{srgName, mojName, desc});
    }

    @Nonnull
    protected static String getJavadocs(Object javadoc) {
        if (javadoc == null)
            return "";
        if (javadoc instanceof String)
            return (String) javadoc; // Parameters don't use an array for some reason
        if (!(javadoc instanceof List))
            return "";
        List<?> list = (List<?>) javadoc;
        StringBuilder sb = new StringBuilder();
        int size = list.size();
        for (int i = 0; i < size; i++) {
            sb.append(list.get(i));
            if (i != size - 1)
                sb.append("\\n");
        }
        return sb.toString();
    }

    protected static IMappingFile findObfToSrg(File mcp, MCPConfigV2 config) throws IOException {
        return IMappingFile.load(new ByteArrayInputStream(Utils.getZipData(mcp, config.getData("mappings"))));
    }

    @Nullable
    protected static File getMCP(Project project, String version) {
        return MavenArtifactDownloader.manual(project, "de.oceanlabs.mcp:mcp_config:" + version + "@zip", false);
    }

    protected static void writeCsv(String name, List<String[]> mappings, Path rootPath) throws IOException {
        if (mappings.size() <= 1)
            return;
        Path csvPath = rootPath.resolve(name);
        try (CsvWriter writer = CsvWriter.builder().lineDelimiter(LineDelimiter.LF).build(csvPath, StandardCharsets.UTF_8)) {
            mappings.forEach(writer::writeRow);
        }
        Files.setLastModifiedTime(csvPath, FileTime.fromMillis(Utils.ZIPTIME));
    }
}
