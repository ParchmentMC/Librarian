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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    protected static final Pattern LETTERS_ONLY_PATTERN = Pattern.compile("[a-zA-Z]+");
    protected static final Pattern LINE_PATTERN = Pattern.compile("\r?\n");
    protected static final Pattern SPACE_PATTERN = Pattern.compile(" ");
    protected static final String SRG_CLASS = "net/minecraft/src/C_";

    @Nonnull
    @Override
    public Set<String> getChannels() {
        return ImmutableSet.of("parchment");
    }

    @Nullable
    @Override
    public File getMappingsFile(MCPRepo mcpRepo, Project project, String channel, String mappingVersion) throws IOException {
        ParchmentMappingVersion version = ParchmentMappingVersion.of(mappingVersion);

        File client = MavenArtifactDownloader.generate(project, "net.minecraft:client:" + version.mcVersion() + ":mappings@txt", true);
        if (client == null)
            throw new IllegalStateException("Could not create " + version.mcVersion() + " official mappings due to missing ProGuard mappings.");

        File mcp = getMCP(project, version.mcpVersion());
        if (mcp == null)
            return null;

        MCPConfigV2 config = MCPConfigV2.getFromArchive(mcp);

        IMappingFile obfToSrg = findObfToSrg(mcp, config);
        if (obfToSrg == null)
            throw new IllegalStateException("Could not create " + version.mcpVersion() + " parchment mappings due to missing MCP's tsrg");

        File dep = getParchmentZip(project, version);

        File mappings = cacheParchment(project, version.mcpVersion(), version.parchmentVersion(), "zip");
        HashStore cache = new HashStore()
                .load(cacheParchment(project, version.mcpVersion(), version.parchmentVersion(), "zip.input"))
                .add("mcp", mcp)
                .add("mcversion", version.mcVersion())
                .add("mappings", dep)
                .add("codever", "3");

        if (cache.isSame() && mappings.exists())
            return mappings;

        VersionedMappingDataContainer mappingData = extractMappingData(dep);

        IMappingFile mojToObf = IMappingFile.load(client);
        IMappingFile mojToSrg = genMojToSrg(obfToSrg, mojToObf);
        IMappingFile srgToMoj = mojToSrg.reverse();
        ListMultimap<String, ConstructorData> constructorMap = getConstructorDataMap(mcp, config);

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

            // This is only used on non-official exports (1.16 and lower)
            if (classData != null && constructorMap != null) {
                List<ConstructorData> list = constructorMap.get(srgClass.getMapped());
                list.forEach(data -> {
                    MethodData methodData = classData.getMethod("<init>", srgToMoj.remapDescriptor(data.descriptor));
                    if (methodData == null)
                        return;

                    StringBuilder mdJavadoc = new StringBuilder(getJavadocs(methodData.getJavadoc()));
                    populateParameters(config.isOfficial(), parameters, data.id, null, methodData, mdJavadoc);
                    populateMappings(methods, srgClass, null, mdJavadoc.toString(), "<init>", "<init>", false);
                });
            }

            srgClass.getFields().forEach(srgField -> {
                FieldData fieldData = classData != null ? classData.getField(srgField.getOriginal()) : null;
                populateMappings(fields, srgClass, srgField, fieldData != null ? fieldData.getJavadoc() : null);
            });

            srgClass.getMethods().forEach(srgMethod -> {
                MethodData methodData = classData != null ? classData.getMethod(srgMethod.getOriginal(), srgMethod.getDescriptor()) : null;
                StringBuilder mdJavadoc = methodData != null ? new StringBuilder(getJavadocs(methodData.getJavadoc())) : new StringBuilder();
                populateParameters(config.isOfficial(), parameters, null, srgMethod, methodData, mdJavadoc);
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

    protected IMappingFile genMojToSrg(IMappingFile obfToSrg, IMappingFile mojToObf) {
        // We remap it this way to preserve parameters and eliminate SRG classnames at the same time
        return obfToSrg.reverse().chain(mojToObf.reverse()).reverse();
    }

    protected void populateParameters(boolean isOfficialExport, List<String[]> parameters, String constructorId, IMethod srgMethod, MethodData methodData, StringBuilder mdJavadoc) {
        if (methodData == null || methodData.getParameters().isEmpty())
            return;

        List<IParameter> srgParams = srgMethod == null ? ImmutableList.of() : ImmutableList.copyOf(srgMethod.getParameters());
        List<ParameterData> methodParams = ImmutableList.copyOf(methodData.getParameters());
        // If the MCPConfig export is official (1.17+) and the # of params doesn't match, we need to skip it.
        // This is because on non-official exports, the parameter SRG id can be reconstructed from method SRG name and parameter JVM index.
        // This is not possible on official exports with the newer parameter SRG format.
        if (constructorId == null && isOfficialExport && methodParams.size() != srgParams.size())
            return;

        for (int i = 0; i < methodParams.size(); i++) {
            ParameterData parameter = methodParams.get(i);
            String srgParam;
            // official export == 1.17+
            if (isOfficialExport) {
                srgParam = srgParams.get(i).getMapped();
            } else if (constructorId != null) {
                srgParam = String.format("p_i%s_%d_", constructorId, parameter.getIndex());
            } else {
                if (srgMethod == null)
                    continue;
                String srgId = srgMethod.getMapped().indexOf('_') == -1
                        ? srgMethod.getMapped()
                        : srgMethod.getMapped().split("_")[1];
                if (LETTERS_ONLY_PATTERN.matcher(srgId).matches())
                    continue; // This means it's a mapped parameter of a functional interface method and we can't use it.
                srgParam = String.format("p_%s_%d_", srgId, parameter.getIndex());
            }
            String paramName = parameter.getName();
            if (paramName != null)
                parameters.add(new String[]{srgParam, paramName, ""});
            String paramJavadoc = getJavadocs(parameter.getJavadoc());
            if (!paramJavadoc.isEmpty())
                mdJavadoc.append("\\n@param ").append(paramName != null ? paramName : srgParam).append(' ').append(paramJavadoc);
        }
    }

    protected File getParchmentZip(Project project, ParchmentMappingVersion version) {
        String artifact = "org.parchmentmc.data:parchment-" + version.mcVersion() + ":" + version.parchmentVersion() + ":checked@zip";
        File dep = getDependency(project, artifact);
        if (dep == null) {
            // TODO remove this later? or keep backwards-compatibility with older releases?
            dep = getDependency(project, artifact.replace(":checked", ""));
        }
        if (dep == null)
            throw new IllegalArgumentException("Could not find Parchment version of " + version.parchmentVersion() + '-' + version.mcVersion() + " with artifact " + artifact);
        return dep;
    }

    private final Map<String, File> dependencyCache = new ConcurrentHashMap<>();
    protected synchronized File getDependency(Project project, String dependencyNotation) {
        File cached = dependencyCache.get(dependencyNotation);
        if (cached != null)
            return cached;

        File dependency = null;
        try {
            Iterator<File> iterator = project.getConfigurations().detachedConfiguration(project.getDependencies().create(dependencyNotation)).resolve().iterator();
            dependency = iterator.hasNext() ? iterator.next() : null;
        } catch (Exception e) {
            project.getLogger().debug("Error when retrieving dependency using Gradle configuration resolution, using MavenArtifactDownloader", e);
        }

        // Fallback to MavenArtifactDownloader, however it doesn't support snapshot versions
        if (dependency == null)
            dependency = MavenArtifactDownloader.manual(project, dependencyNotation, false);

        if (dependency != null)
            dependencyCache.put(dependencyNotation, dependency);
        return dependency;
    }
    
    protected VersionedMappingDataContainer extractMappingData(File dep) throws IOException {
        try (ZipFile zip = new ZipFile(dep)) {
            ZipEntry entry = zip.getEntry("parchment.json");
            if (entry == null)
                throw new IllegalStateException("Parchment zip did not contain \"parchment.json\"");

            return GSON.fromJson(new InputStreamReader(zip.getInputStream(entry)), VersionedMappingDataContainer.class);
        }
    }

    @Nonnull
    protected Path getCacheBase(Project project) {
        File gradleUserHomeDir = project.getGradle().getGradleUserHomeDir();
        return Paths.get(gradleUserHomeDir.getPath(), "caches", "parchmentgradle");
    }

    @Nonnull
    protected File getCache(Project project, String... tail) {
        return Paths.get(getCacheBase(project).toString(), tail).toFile();
    }

    @Nonnull
    protected File cacheParchment(Project project, String mcpversion, String mappingsVersion, String ext) {
        return getCache(project, "org", "parchmentmc", "data", "parchment-" + mcpversion, mappingsVersion, "parchment-" + mcpversion + '-' + mappingsVersion + '.' + ext);
    }

    protected void populateMappings(List<String[]> mappings, IClass srgClass, INode srgNode, Object javadoc) {
        String desc = getJavadocs(javadoc);
        if (srgNode instanceof IPackage || srgNode instanceof IClass) {
            boolean isSrgClass = srgNode.getMapped().startsWith(SRG_CLASS);
            // If it's a srg classname then it means we should always use the mojmap classname
            String name = (isSrgClass ? srgNode.getOriginal() : srgNode.getMapped()).replace('/', '.');
            // TODO fix InstallerTools so that we don't have to expand the csv size for no reason
            if (!desc.isEmpty())
                mappings.add(new String[]{name, name, desc});
            return;
        }
        String srgName = srgNode.getMapped();
        String mojName = srgNode.getOriginal();
        boolean isSrg = srgName.startsWith("p_") || srgName.startsWith("func_") || srgName.startsWith("m_") || srgName.startsWith("field_") || srgName.startsWith("f_");
        populateMappings(mappings, srgClass, srgNode, desc, srgName, mojName, isSrg);
    }

    protected void populateMappings(List<String[]> mappings, IClass srgClass, INode srgNode, String desc, String srgName, String mojName, boolean isSrg) {
        // If it's not a srg id and has javadocs, we need to add the class to the beginning as it is a special method/field of some kind
        if (!isSrg && !desc.isEmpty() && (srgNode instanceof IMethod || srgNode instanceof IField || srgName.equals("<init>"))) {
            boolean isSrgClass = srgClass.getMapped().startsWith(SRG_CLASS);
            // If it's a srg classname then it means we should always use the mojmap classname
            srgName = (isSrgClass ? srgClass.getOriginal() : srgClass.getMapped()).replace('/', '.') + '#' + srgName;
        }
        // Only add to the mappings list if it is mapped or has javadocs
        if ((isSrg && !srgName.equals(mojName)) || !desc.isEmpty())
            mappings.add(new String[]{srgName, mojName, desc});
    }

    @Nonnull
    protected String getJavadocs(Object javadoc) {
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

    protected IMappingFile findObfToSrg(File mcp, MCPConfigV2 config) throws IOException {
        return IMappingFile.load(new ByteArrayInputStream(Utils.getZipData(mcp, config.getData("mappings"))));
    }

    protected ListMultimap<String, ConstructorData> getConstructorDataMap(File mcp, MCPConfigV2 config) throws IOException {
        if (config.isOfficial())
            return null;

        String data = new String(Utils.getZipData(mcp, config.getData("constructors")), StandardCharsets.UTF_8);
        return LINE_PATTERN.splitAsStream(data)
                .map(SPACE_PATTERN::split)
                .collect(Multimaps.toMultimap(split -> split[1], ConstructorData::new, MultimapBuilder.hashKeys().arrayListValues()::build));
    }

    @Nullable
    protected File getMCP(Project project, String version) {
        return MavenArtifactDownloader.manual(project, "de.oceanlabs.mcp:mcp_config:" + version + "@zip", false);
    }

    protected void writeCsv(String name, List<String[]> mappings, Path rootPath) throws IOException {
        if (mappings.size() <= 1)
            return;
        Path csvPath = rootPath.resolve(name);
        try (CsvWriter writer = CsvWriter.builder().lineDelimiter(LineDelimiter.LF).build(csvPath, StandardCharsets.UTF_8)) {
            mappings.forEach(writer::writeRow);
        }
        Files.setLastModifiedTime(csvPath, FileTime.fromMillis(Utils.ZIPTIME));
    }

    protected static class ConstructorData {
        protected final String id;
        protected final String classHolder;
        protected final String descriptor;

        protected ConstructorData(String[] split) {
            this(split[0], split[1], split[2]);
        }

        protected ConstructorData(String id, String classHolder, String descriptor) {
            this.id = id;
            this.classHolder = classHolder;
            this.descriptor = descriptor;
        }
    }
}
