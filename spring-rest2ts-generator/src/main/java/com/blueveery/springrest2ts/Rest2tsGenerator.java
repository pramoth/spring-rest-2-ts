package com.blueveery.springrest2ts;

import com.blueveery.springrest2ts.converters.*;
import com.blueveery.springrest2ts.filters.JavaTypeFilter;
import com.blueveery.springrest2ts.filters.RejectJavaTypeFilter;
import com.blueveery.springrest2ts.tsmodel.TSModule;
import com.blueveery.springrest2ts.tsmodel.TSType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;

/**
 * Created by tomaszw on 30.07.2017.
 */
public class Rest2tsGenerator {

    static Logger logger = LoggerFactory.getLogger("gen-logger");
    private Map<Class, TSType> customTypeMapping = new HashMap<>();

    private JavaTypeFilter modelClassesCondition = new RejectJavaTypeFilter();
    private JavaTypeFilter restClassesCondition = new RejectJavaTypeFilter();

    private NullableTypesStrategy nullableTypesStrategy = new DefaultNullableTypesStrategy();

    private JavaPackageToTsModuleConverter javaPackageToTsModuleConverter = new TsModuleCreatorConverter(2);
    private ComplexTypeConverter enumConverter = new JavaEnumToTsEnumConverter();;
    private ComplexTypeConverter modelClassesConverter;
    private ComplexTypeConverter restClassesConverter;

    public Map<Class, TSType> getCustomTypeMapping() {
        return customTypeMapping;
    }

    public void setModelClassesCondition(JavaTypeFilter modelClassesCondition) {
        this.modelClassesCondition = modelClassesCondition;
    }

    public void setRestClassesCondition(JavaTypeFilter restClassesCondition) {
        this.restClassesCondition = restClassesCondition;
    }

    public void setJavaPackageToTsModuleConverter(JavaPackageToTsModuleConverter javaPackageToTsModuleConverter) {
        this.javaPackageToTsModuleConverter = javaPackageToTsModuleConverter;
    }

    public void setEnumConverter(ComplexTypeConverter enumConverter) {
        this.enumConverter = enumConverter;
    }

    public void setModelClassesConverter(ComplexTypeConverter modelClassesConverter) {
        this.modelClassesConverter = modelClassesConverter;
    }

    public void setRestClassesConverter(ComplexTypeConverter restClassesConverter) {
        this.restClassesConverter = restClassesConverter;
    }

    public void setNullableTypesStrategy(NullableTypesStrategy nullableTypesStrategy) {
        this.nullableTypesStrategy = nullableTypesStrategy;
    }

    public SortedSet<TSModule> generate(Set<String> packagesNames, Path outputDir) throws IOException {
        Set<Class> modelClasses = new HashSet<>();
        Set<Class> restClasses = new HashSet<>();
        Set<Class> enumClasses = new HashSet<>();

        logger.info("Scanning model classes");
        List<Class> loadedClasses= loadClasses(packagesNames);
        searchClasses(loadedClasses, modelClassesCondition, modelClasses, enumClasses, logger);
        logger.info("Scanning rest controllers classes");
        searchClasses(loadedClasses, restClassesCondition, restClasses, enumClasses, logger);


        registerCustomTypesMapping(customTypeMapping);

        exploreRestClasses(restClasses, modelClassesCondition, modelClasses);
        exploreModelClasses(modelClasses, restClassesCondition);

        convertModules(enumClasses, javaPackageToTsModuleConverter);
        convertModules(modelClasses, javaPackageToTsModuleConverter);
        convertModules(restClasses, javaPackageToTsModuleConverter);

        convertTypes(enumClasses, javaPackageToTsModuleConverter, enumConverter);
        if (!modelClasses.isEmpty()) {
            if (modelClassesConverter == null) {
                throw new IllegalStateException("Model classes converter is not set");
            }
            convertTypes(modelClasses, javaPackageToTsModuleConverter, modelClassesConverter);
        }

        if (!restClasses.isEmpty()) {
            if (restClassesConverter == null) {
                throw new IllegalStateException("Rest classes converter is not set");
            }
            convertTypes(restClasses, javaPackageToTsModuleConverter, restClassesConverter);
        }


        writeTSModules(javaPackageToTsModuleConverter.getTsModules(), outputDir, logger);

        return javaPackageToTsModuleConverter.getTsModules();
    }

    private void registerCustomTypesMapping(Map<Class, TSType> customTypeMapping) {
        for (Class nextJavaType : customTypeMapping.keySet()) {
            TSType tsType = customTypeMapping.get(nextJavaType);
            TypeMapper.registerTsType(nextJavaType, tsType);
        }
    }

    private void writeTSModules(SortedSet<TSModule> tsModuleSortedSet, Path outputDir, Logger logger) throws IOException {
        for (TSModule tsModule : tsModuleSortedSet) {
            tsModule.writeModule(outputDir, logger);
        }
    }

    private void convertModules(Set<Class> javaClasses, JavaPackageToTsModuleConverter javaPackageToTsModuleConverter) {
        for (Class javaType : javaClasses) {
            javaPackageToTsModuleConverter.mapJavaTypeToTsModule(javaType);
        }
    }

    private void convertTypes(Set<Class> javaTypes, JavaPackageToTsModuleConverter tsModuleSortedMap, ComplexTypeConverter complexTypeConverter) {
        Set<Class> preConvertedTypes = new HashSet<>();
        for (Class javaType : javaTypes) {
            if (complexTypeConverter.preConverted(tsModuleSortedMap, javaType)) {
                preConvertedTypes.add(javaType);
            }
        }

        for (Class javaType : preConvertedTypes) {
            complexTypeConverter.convert(javaType, nullableTypesStrategy);
        }

    }

    private void exploreModelClasses(Set<Class> modelClasses, JavaTypeFilter javaTypeFilter) {

    }

    private void exploreRestClasses(Set<Class> restClasses, JavaTypeFilter javaTypeFilter, Set<Class> modelClasses) {

    }

    private void searchClasses(List<Class> loadedClasses, JavaTypeFilter javaTypeFilter, Set<Class> classSet, Set<Class> enumClassSet, Logger logger) throws IOException {
        for (Class foundClass : loadedClasses) {
            logger.info(String.format("Found class : %s", foundClass.getName()));
            if (Enum.class.isAssignableFrom(foundClass)) {
                logger.info(String.format("Found enum class : %s", foundClass.getName()));
                enumClassSet.add(foundClass);
                continue;
            }

            if (javaTypeFilter.accept(foundClass)) {
                classSet.add(foundClass);
            }else{
                logger.warn(String.format("Class filtered out : %s", foundClass.getSimpleName()));
            }
            javaTypeFilter.explain(foundClass, logger, "");
        }
    }


    private List<Class> loadClasses(Set<String> packageSet) throws IOException {
        ClassLoader classLoader = this.getClass().getClassLoader();
        if(!(classLoader instanceof URLClassLoader)){
            throw new IllegalStateException("Generator must be run under URLClassLoader to scan classes, current ClassLoader is : " + classLoader.getClass().getSimpleName());
        }
        URLClassLoader currentClassLoader = (URLClassLoader) classLoader;
        List<Class> classList = new ArrayList<>();
        for (String packageName : packageSet) {
            Enumeration<URL> urlEnumeration = currentClassLoader.findResources(packageName.replace(".", "/"));
            while (urlEnumeration.hasMoreElements()) {
                URL url = urlEnumeration.nextElement();
                scanPackagesRecursively(currentClassLoader, url, packageName, classList);
            }
        }
        return classList;
    }

    private void scanPackagesRecursively(URLClassLoader classLoader, URL url, String packageName, List<Class> classList) throws IOException {
        try(InputStream inputStream = url.openStream()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(inputStream));
            } catch (Exception e) {
                System.out.println("Failed to open package : " + packageName);
                return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains(".")) {
                    String newPackageName = packageName + "/" + line;
                    scanPackagesRecursively(classLoader, new URL(url.toString() + "/" + line), newPackageName, classList);                } else {
                    if (line.endsWith(".class")) {
                        String className = (packageName + "/" + line).replace(".class", "").replace("/", ".");
                        try {
                            Class<?> loadedClass = classLoader.loadClass(className);
                            if (!loadedClass.isAnnotation()) {
                                addNestedClasses(loadedClass.getDeclaredClasses(), classList);
                                classList.add(loadedClass);
                            }
                        } catch (Exception e) {
                            System.out.println(String.format("Failed to lad class %s due to error %s:%s", className, e.getClass().getSimpleName(), e.getMessage()));
                        }
                    }
                }
            }
        }
    }

    private void addNestedClasses(Class<?>[] nestedClasses, List<Class> classList) {
        for (Class<?> nestedClass : nestedClasses) {
            if (!nestedClass.isAnnotation()) {
                classList.add(nestedClass);
            }
            addNestedClasses(nestedClass.getDeclaredClasses(), classList);
        }
    }

}
