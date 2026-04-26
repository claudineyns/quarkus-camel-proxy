package br.dev.claudiney.proxy.converter;

import org.apache.camel.Converter;
import org.apache.camel.TypeConverters;
import java.io.File;

@Converter(generateLoader = true)
public class FileConverter implements TypeConverters {

    @Converter
    public static File toFile(String path) {
        return new File(path);
    }
}
