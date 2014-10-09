/* (c) 2014 Boundless, http://boundlessgeo.com
 * This code is licensed under the GPL 2.0 license.
 */
package com.boundlessgeo.geoserver.api.controllers;

import com.boundlessgeo.geoserver.json.JSONArr;
import com.boundlessgeo.geoserver.json.JSONObj;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.ResourcePool;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.util.CloseableIterator;
import org.geoserver.config.GeoServer;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.platform.resource.Files;
import org.geoserver.platform.resource.Paths;
import org.geotools.data.ows.Layer;
import org.geotools.util.Converters;
import org.geotools.util.logging.Logging;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;

import static org.geoserver.catalog.Predicates.and;
import static org.geoserver.catalog.Predicates.equal;

/**
 * Used to connect to data storage (file, database, or service).
 * <p>
 * This API is locked down for map composer and is (not intended to be stable between releases).</p>
 * 
 * @see <a href="https://github.com/boundlessgeo/suite/wiki/Stores-API">Store API</a> (Wiki)
 */
 @Controller
 @RequestMapping("/api/stores")
 public class StoreController extends ApiController {
     static Logger LOG = Logging.getLogger(StoreController.class);

    @Autowired
    public StoreController(GeoServer geoServer) {
        super(geoServer);
    }

    @RequestMapping(value = "/{wsName}", method = RequestMethod.GET)
    public @ResponseBody
    JSONArr list(@PathVariable String wsName){
        JSONArr arr = new JSONArr();
        Catalog cat = geoServer.getCatalog();
        for (StoreInfo store : cat.getStoresByWorkspace(wsName, StoreInfo.class)) {
            store(arr.addObject(), store);
        }
        return arr;
    }
    
    @RequestMapping(value = "/{wsName}/{name}", method = RequestMethod.GET)
    public @ResponseBody
    JSONObj get(@PathVariable String wsName, @PathVariable String name) {
        StoreInfo store = findStore(wsName, name, geoServer.getCatalog());
        if( store == null ){
            throw new IllegalArgumentException("Store "+wsName+":"+name+" not found");
        }

        try {
            return storeDetails(new JSONObj(), store);
        }
        catch(IOException e) {
            throw new RuntimeException(String.format("Error occured accessing store: %s,%s", wsName, name), e);
        }
    }

    public enum Type {FILE,DATABASE,WEB,GENERIC;
        static Type of( StoreInfo store ){
            if( store instanceof CoverageStoreInfo){
                String url = ((CoverageStoreInfo)store).getURL();
                if( url.startsWith("file")){
                    return Type.FILE;
                }
                else if( url.startsWith("http") ||
                         url.startsWith("https") ||
                         url.startsWith("ftp") ||
                         url.startsWith("sftp")){
                    return Type.WEB;
                }
            }
            Map<String, Serializable> params = store.getConnectionParameters();
            if( params.containsKey("dbtype")){
                return Type.DATABASE;
            }
            if( store instanceof WMSStoreInfo){
                return Type.WEB;
            }
            if( params.keySet().contains("directory") ||
                params.keySet().contains("file") ){
                
                return Type.FILE;
            }
            for( Object value : params.values()){
                if( value == null ) continue;
                if( value instanceof File ||
                    (value instanceof String && ((String)value).startsWith("file:")) ||
                    (value instanceof URL && ((URL)value).getProtocol().equals("file"))){
                    return Type.FILE;
                }
                if( (value instanceof String && ((String)value).startsWith("http:")) ||
                    (value instanceof URL && ((URL)value).getProtocol().equals("http"))){
                    return Type.WEB;
                }
                if( value instanceof String && ((String)value).startsWith("jdbc:")){
                    return Type.DATABASE;
                }
            }
            return Type.GENERIC;
        }        
    }
    public enum Kind {RASTER,VECTOR,SERVICE,UNKNOWN;
        static Kind of( StoreInfo store ){
            if( store instanceof CoverageStoreInfo){
                return Kind.RASTER;
            }
            else if( store instanceof DataStoreInfo){
                return Kind.VECTOR;
            }
            else if(store instanceof WMSStoreInfo){
                return Kind.SERVICE;
            }
            return Kind.UNKNOWN;
        }
    }

    JSONObj store(JSONObj obj, StoreInfo store) {       
        String name = store.getName();

        obj.put("name", name)
            .put("workspace", store.getWorkspace().getName())
            .put("enabled", store.isEnabled())
            .put("description", store.getDescription())
            .put("format", store.getType());
        
        String source = source(store);
        obj.put("source", source )
           .put("type", Type.of(store).name())
           .put("kind", Kind.of(store).name());   

        return IO.metadata(obj, store);
    }

    JSONObj storeDetails(JSONObj json, StoreInfo store) throws IOException {
        store(json, store);

        JSONObj connection = new JSONObj();
        Map<String, Serializable> params = store.getConnectionParameters();
        for( Entry<String,Serializable> param : params.entrySet() ){
            String key = param.getKey();
            Object value = param.getValue();
            String text = value.toString();
            
            connection.put( key, text );
        }
        if (store instanceof CoverageStoreInfo) {
            CoverageStoreInfo info = (CoverageStoreInfo) store;
            connection.put("raster", info.getURL());
        }
        if (store instanceof WMSStoreInfo) {
            WMSStoreInfo info = (WMSStoreInfo) store;
            json.put("wms", info.getCapabilitiesURL());
        }
        json.put("connection", connection );
        
        Throwable error = store.getError();
        if (error != null) {
            json.putObject("error")
                .put("message", error.getMessage())
                .put("trace", Throwables.getStackTraceAsString(error));
        }

        layers(store, json.putArray("layers"));

        if(store.isEnabled()){
            resources(store, json.putArray("resources"));
        }

        return json;
    }

    JSONArr layers(StoreInfo store, JSONArr list) throws IOException {
        Catalog cat = geoServer.getCatalog();
        WorkspaceInfo ws = store.getWorkspace();

        Filter filter = and(equal("store", store), equal("namespace.prefix", ws.getName()));
        try (
            CloseableIterator<ResourceInfo> layers = cat.list(ResourceInfo.class, filter);
        ) {
            while(layers.hasNext()) {
                ResourceInfo r = layers.next();
                list.addObject().put("name", r.getName()).put("workspace", ws.getName());
            }
        }

        return list;
    }

    JSONArr resources(StoreInfo store, JSONArr list) throws IOException {
        Catalog cat = geoServer.getCatalog();
        WorkspaceInfo ws = store.getWorkspace();

        for (String resource : listResources(store)) {
            JSONObj obj = list.addObject();
            obj.put("name", resource);

            Filter filter = and(equal("namespace.prefix", ws.getName()), equal("nativeName", resource));
            try (
                CloseableIterator<ResourceInfo> published = cat.list(ResourceInfo.class, filter);
            ) {
                JSONArr layers = obj.putArray("layers");
                while(published.hasNext()) {
                    ResourceInfo r = published.next();
                    layers.addObject().put("name", r.getName()).put("workspace", ws.getName());
                }
            }
        }

        return list;
    }

    Iterable<String> listResources(StoreInfo store) throws IOException {
        if (store instanceof DataStoreInfo) {
            return Iterables.transform(((DataStoreInfo) store).getDataStore(null).getNames(),
                new Function<Name, String>() {
                    @Nullable
                    @Override
                    public String apply(@Nullable Name input) {
                        return input.getLocalPart();
                    }
                });
        }
        else if (store instanceof CoverageStoreInfo) {
            return Arrays.asList(((CoverageStoreInfo) store).getGridCoverageReader(null, null).getGridCoverageNames());
        }
        else if (store instanceof WMSStoreInfo) {
            return Iterables.transform(((WMSStoreInfo) store).getWebMapServer(null).getCapabilities().getLayerList(),
                new Function<Layer, String>() {
                    @Nullable
                    @Override
                    public String apply(@Nullable Layer input) {
                        return input.getName();
                    }
                });
        }

        throw new IllegalStateException("Unrecognized store type");
    }

    private String source(StoreInfo store) {
        if( store instanceof CoverageStoreInfo ){
            CoverageStoreInfo coverage = (CoverageStoreInfo) store;
            return sourceURL( coverage.getURL() );
        }
        GeoServerResourceLoader resourceLoader = geoServer.getCatalog().getResourceLoader();
        Map<String, Serializable> params =
                ResourcePool.getParams( store.getConnectionParameters(), resourceLoader );
        if( params.containsKey("dbtype")){
            // See JDBCDataStoreFactory for details
            String host = Converters.convert(params.get("host"),  String.class);
            String port = Converters.convert(params.get("port"),  String.class);
            String dbtype = Converters.convert(params.get("dbtype"),  String.class);
            String schema = Converters.convert(params.get("schema"),  String.class);
            String database = Converters.convert(params.get("database"),  String.class);
            StringBuilder source = new StringBuilder();
            source.append(host);
            if( port != null ){
                source.append(':').append(port);
            }
            source.append('/').append(dbtype).append('/').append(database);
            if( schema != null ){
                source.append('/').append(schema);
            }
            return source.toString();
        }
        else if( store instanceof WMSStoreInfo){
            String url = ((WMSStoreInfo)store).getCapabilitiesURL();
            return url;
        }
        else if( params.keySet().contains("directory")){
            String directory = Converters.convert(params.get("directory"),String.class);
            return sourceFile( directory );
        }
        else if( params.keySet().contains("file")){
            String file = Converters.convert(params.get("file"),String.class);
            return sourceFile( file );
        }
        if( params.containsKey("url")){
            String url = Converters.convert(params.get("url"),String.class);
            return sourceURL( url );
        }
        for( Object value : params.values() ){
            if( value instanceof URL ){
                return source( (URL) value );
            }
            if( value instanceof File ){
                return source( (File) value );
            }
            if( value instanceof String ){
                String text = (String) value;
                if( text.startsWith("file:")){
                    return sourceURL( text );
                }
                else if ( text.startsWith("http:") || text.startsWith("https:") || text.startsWith("ftp:")){
                    return text;
                }
            }
        }
        return "undertermined";
    }
    
    String source( File file ){
        File baseDirectory = dataDir().getResourceLoader().getBaseDirectory();
        return file.isAbsolute() ? file.toString() : Paths.convert(baseDirectory,file);
    }
    String source( URL url ){
        File baseDirectory = dataDir().getResourceLoader().getBaseDirectory();
        
        if( url.getProtocol().equals("file")){
            File file = Files.url(baseDirectory,url.toExternalForm());
            if( file != null && !file.isAbsolute() ){
                return Paths.convert(baseDirectory, file); 
            }
        }
        return url.toExternalForm();
    }
    String sourceURL( String  url ){
        File baseDirectory = dataDir().getResourceLoader().getBaseDirectory();

        File file = Files.url(baseDirectory,url);
        if( file != null ){
            return Paths.convert(baseDirectory, file); 
        }
        return url;
    }
    String sourceFile( String file ){
        File baseDirectory = dataDir().getResourceLoader().getBaseDirectory();

        File f = new File( file );
        return f.isAbsolute() ? file : Paths.convert(baseDirectory,f);
    }
}
