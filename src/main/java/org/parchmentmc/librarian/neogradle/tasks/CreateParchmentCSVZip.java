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

package org.parchmentmc.librarian.neogradle.tasks;

import com.google.common.collect.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import net.minecraftforge.srgutils.IMappingFile;
import net.neoforged.gradle.common.runtime.tasks.DefaultRuntime;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.parchmentmc.feather.io.gson.MDCGsonAdapterFactory;
import org.parchmentmc.feather.io.gson.NamedAdapter;
import org.parchmentmc.feather.io.gson.OffsetDateTimeAdapter;
import org.parchmentmc.feather.io.gson.SimpleVersionAdapter;
import org.parchmentmc.feather.io.gson.metadata.MetadataAdapterFactory;
import org.parchmentmc.feather.mapping.MappingDataContainer;
import org.parchmentmc.feather.mapping.VersionedMappingDataContainer;
import org.parchmentmc.feather.named.Named;
import org.parchmentmc.feather.util.SimpleVersion;

import javax.annotation.Nonnull;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@CacheableTask
public abstract class CreateParchmentCSVZip extends DefaultRuntime {

    private static final Pattern LETTERS_ONLY_PATTERN = Pattern.compile("[a-zA-Z]+");
    private static final Pattern LINE_PATTERN = Pattern.compile("\r?\n");
    private static final Pattern SPACE_PATTERN = Pattern.compile(" ");
    private static final Pattern DESCRIPTOR_OBJECT_PATTERN = Pattern.compile("L.+?;");
    private static final Pattern DESCRIPTOR_ARRAY_PATTERN = Pattern.compile("\\[+.");
    private static final String SRG_CLASS = "net/minecraft/src/C_";

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(new MDCGsonAdapterFactory())
            .registerTypeAdapter(SimpleVersion.class, new SimpleVersionAdapter())
            .registerTypeAdapterFactory(new MetadataAdapterFactory())
            .registerTypeAdapter(Named.class, new NamedAdapter())
            .registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeAdapter())
            .create();

    @TaskAction
    public void create() throws IOException {
        VersionedMappingDataContainer mappingData = extractMappingData();

        IMappingFile obfToSrg = IMappingFile.load(getNeoFormMappings().getAsFile().get());
        IMappingFile mojToObf = IMappingFile.load(getClientMappings().getAsFile().get());
        IMappingFile mojToSrg = obfToSrg.reverse().chain(mojToObf.reverse()).reverse();
        IMappingFile srgToMoj = mojToSrg.reverse();
        ListMultimap<String, ConstructorData> constructorMap = getConstructorDataMap();

        // All the CSV data holders
        String[] header = {"searge", "name", "desc"};
        List<String[]> packages = Lists.<String[]>newArrayList(header);
        List<String[]> classes = Lists.<String[]>newArrayList(header);
        List<String[]> fields = Lists.<String[]>newArrayList(header);
        List<String[]> methods = Lists.<String[]>newArrayList(header);
        List<String[]> parameters = Lists.<String[]>newArrayList(header);

        mojToSrg.getPackages().forEach(srgPackage -> {
            MappingDataContainer.PackageData packageData = mappingData.getPackage(srgPackage.getOriginal());
            populateMappings(packages, null, srgPackage, packageData != null ? packageData.getJavadoc() : null);
        });

        mojToSrg.getClasses().forEach(srgClass -> {
            MappingDataContainer.ClassData classData = mappingData.getClass(srgClass.getOriginal());
            populateMappings(classes, srgClass, srgClass, classData != null ? classData.getJavadoc() : null);

            // This is only used on non-official exports (1.16 and lower)
            if (classData != null && constructorMap != null) {
                List<ConstructorData> list = constructorMap.get(srgClass.getMapped());
                list.forEach(data -> {
                    MappingDataContainer.MethodData methodData = classData.getMethod("<init>", srgToMoj.remapDescriptor(data.descriptor));
                    if (methodData == null)
                        return;

                    StringBuilder mdJavadoc = new StringBuilder(getJavadocs(methodData.getJavadoc()));
                    populateParameters(parameters, data.id, null, methodData, mdJavadoc);
                    populateMappings(methods, srgClass, null, mdJavadoc.toString(), "<init>", "<init>", false);
                });
            }

            srgClass.getFields().forEach(srgField -> {
                MappingDataContainer.FieldData fieldData = classData != null ? classData.getField(srgField.getOriginal()) : null;
                populateMappings(fields, srgClass, srgField, fieldData != null ? fieldData.getJavadoc() : null);
            });

            srgClass.getMethods().forEach(srgMethod -> {
                MappingDataContainer.MethodData methodData = classData != null ? classData.getMethod(srgMethod.getOriginal(), srgMethod.getDescriptor()) : null;
                StringBuilder mdJavadoc = methodData != null ? new StringBuilder(getJavadocs(methodData.getJavadoc())) : new StringBuilder();
                populateParameters(parameters, null, srgMethod, methodData, mdJavadoc);
                populateMappings(methods, srgClass, srgMethod, mdJavadoc.toString());
            });
        });

        final File mappings = ensureFileWorkspaceReady(getOutput());
        
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
    }


    private void populateParameters(List<String[]> parameters, String constructorId, IMappingFile.IMethod srgMethod, MappingDataContainer.MethodData methodData, StringBuilder mdJavadoc) {
        if (methodData == null || methodData.getParameters().isEmpty())
            return;

        List<IMappingFile.IParameter> srgParams = srgMethod == null ? ImmutableList.of() : ImmutableList.copyOf(srgMethod.getParameters());
        List<MappingDataContainer.ParameterData> methodParams = ImmutableList.copyOf(methodData.getParameters());

        if (getIsOfficial().get() && srgMethod == null)
            return;

        for (MappingDataContainer.ParameterData parameter : methodParams) {
            String srgParam;
            // official export == 1.17+
            if (getIsOfficial().get()) {
                int srgIdx = convertJvmIndexToSrgIndex(Objects.requireNonNull(srgMethod), parameter.getIndex());
                if (srgIdx >= srgParams.size()) {
                    // Something went wrong; cross-version mappings use can cause this.
                    // Skip this parameter.
                    continue;
                }
                srgParam = srgParams.get(srgIdx).getMapped();
            } else if (constructorId != null) {
                srgParam = String.format("p_i%s_%d_", constructorId, parameter.getIndex());
            } else {
                if (srgMethod == null)
                    continue;
                String srgId = srgMethod.getMapped().indexOf('_') == -1
                        ? srgMethod.getMapped()
                        : srgMethod.getMapped().split("_")[1];
                if (LETTERS_ONLY_PATTERN.matcher(srgId).matches())
                    continue; // This means it's a mapped parameter of a functional interface method, and we can't use it.
                srgParam = String.format("p_%s_%d_", srgId, parameter.getIndex());
            }
            String paramName = parameter.getName();
            // Canonical record constructors have a special exception in NeoForm data where the parameter names use their matching field name to support recompilation.
            // Remapping these special field parameter names is a mistake. See https://github.com/ParchmentMC/Librarian/issues/5
            // So to fix it, we filter out any parameter names that start with "f_" aka field.
            if (paramName != null && !srgParam.startsWith("f_"))
                parameters.add(new String[]{srgParam, paramName, ""});
            String paramJavadoc = getJavadocs(parameter.getJavadoc());
            if (!paramJavadoc.isEmpty())
                mdJavadoc.append("\\n@param ").append(paramName != null ? paramName : srgParam).append(' ').append(paramJavadoc);
        }
    }

    private void populateMappings(List<String[]> mappings, IMappingFile.IClass srgClass, IMappingFile.INode srgNode, Object javadoc) {
        String desc = getJavadocs(javadoc);
        if (srgNode instanceof IMappingFile.IPackage || srgNode instanceof IMappingFile.IClass) {
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

    private void populateMappings(List<String[]> mappings, IMappingFile.IClass srgClass, IMappingFile.INode srgNode, String desc, String srgName, String mojName, boolean isSrg) {
        // If it's not a srg id and has javadocs, we need to add the class to the beginning as it is a special method/field of some kind
        if (!isSrg && !desc.isEmpty() && (srgNode instanceof IMappingFile.IMethod || srgNode instanceof IMappingFile.IField || srgName.equals("<init>"))) {
            boolean isSrgClass = srgClass.getMapped().startsWith(SRG_CLASS);
            // If it's a srg classname then it means we should always use the mojmap classname
            srgName = (isSrgClass ? srgClass.getOriginal() : srgClass.getMapped()).replace('/', '.') + '#' + srgName;
        }
        // Only add to the mappings list if it is mapped or has javadocs
        if ((isSrg && !srgName.equals(mojName)) || !desc.isEmpty())
            mappings.add(new String[]{srgName, mojName, desc});
    }

    @Nonnull
    private String getJavadocs(Object javadoc) {
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

    /**
     * Converts a JVM parameter index (as used by the parchment export)
     * to a SRG parameter index using the SRG method data.
     */
    private int convertJvmIndexToSrgIndex(IMappingFile.IMethod srgMethod, int jvmIndex) {
        String args = srgMethod.getDescriptor().substring(1, srgMethod.getDescriptor().lastIndexOf(')'));
        args = DESCRIPTOR_OBJECT_PATTERN.matcher(args).replaceAll("L");
        // Arrays are references always with a size of one regardless of the array type
        args = DESCRIPTOR_ARRAY_PATTERN.matcher(args).replaceAll("L");
        // Non-static methods have an implicit this argument
        int currentIdx = srgMethod.getMetadata().containsKey("is_static") ? 0 : 1;
        int srgIdx = 0;
        while (currentIdx < jvmIndex) {
            // long and double increase the jvm index by 2
            if (srgIdx < args.length() && (args.charAt(srgIdx) == 'J' || args.charAt(srgIdx) == 'D')) {
                currentIdx += 2;
            } else {
                currentIdx++;
            }
            srgIdx++;
        }
        return srgIdx;
    }


    private VersionedMappingDataContainer extractMappingData() throws IOException {
        try (ZipFile zip = new ZipFile(getParchment().getAsFile().get())) {
            ZipEntry entry = zip.getEntry("parchment.json");
            if (entry == null)
                throw new IllegalStateException("Parchment zip did not contain \"parchment.json\"");

            return GSON.fromJson(new InputStreamReader(zip.getInputStream(entry)), VersionedMappingDataContainer.class);
        }
    }

    private ListMultimap<String, ConstructorData> getConstructorDataMap() throws IOException {
        if (getIsOfficial().getOrElse(false))
            return null;

        String data = new String(Files.readAllBytes(getConstructorsData().getAsFile().get().toPath()), StandardCharsets.UTF_8);
        return LINE_PATTERN.splitAsStream(data)
                .map(SPACE_PATTERN::split)
                .collect(Multimaps.toMultimap(split -> split[1], ConstructorData::new, MultimapBuilder.hashKeys().arrayListValues()::build));
    }

    private void writeCsv(String name, List<String[]> mappings, Path rootPath) throws IOException {
        if (mappings.size() <= 1)
            return;
        Path csvPath = rootPath.resolve(name);
        try (CsvWriter writer = CsvWriter.builder().lineDelimiter(LineDelimiter.LF).build(csvPath, StandardCharsets.UTF_8)) {
            mappings.forEach(writer::writeRow);
        }
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getClientMappings();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getNeoFormMappings();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getParchment();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract RegularFileProperty getConstructorsData();

    @Input
    @Optional
    public abstract Property<Boolean> getIsOfficial();

    private static class ConstructorData {
        private final String id;
        private final String descriptor;

        private ConstructorData(String[] split) {
            this(split[0], split[2]);
        }

        private ConstructorData(String id, String descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }
    }

}
