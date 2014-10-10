/* (c) 2014 Boundless, http://boundlessgeo.com
 * This code is licensed under the GPL 2.0 license.
 */
package com.boundlessgeo.geoserver.api.controllers;

import com.boundlessgeo.geoserver.json.JSONArr;
import com.boundlessgeo.geoserver.json.JSONObj;
import com.google.common.base.Throwables;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;

import org.apache.commons.httpclient.util.DateParseException;
import org.apache.commons.httpclient.util.DateUtil;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.Info;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.WMSLayerInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.wms.WMSInfo;
import org.geotools.geometry.jts.Geometries;
import org.geotools.referencing.CRS;
import org.geotools.util.logging.Logging;
import org.ocpsoft.pretty.time.PrettyTime;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.crs.ProjectedCRS;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Date;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper for encoding/decoding objects to/from JSON.
 */
public class IO {

    static Logger LOG = Logging.getLogger(IO.class);

    /**
     * Encodes a projection within the specified object.
     *
     * @return The object passed in.
     */
    public static JSONObj proj(JSONObj obj, CoordinateReferenceSystem crs, String srs) {
        if (srs == null && crs != null) {
            try {
                srs = CRS.lookupIdentifier(crs, false);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Unable to determine srs from crs: " + crs, e);
            }
        }

        if (srs != null) {
            obj.put("srs", srs);
        }

        if (crs == null && srs != null) {
            try {
                crs = CRS.decode(srs);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Unable to determine crs from srs: " + srs, e);
            }
        }

        if (crs != null) {
            // type
            obj.put("type",
                    crs instanceof ProjectedCRS ? "projected" : crs instanceof GeographicCRS ? "geographic" : "other");

            // units
            String units = null;
            try {
                // try to determine from actual crs
                String unit = crs.getCoordinateSystem().getAxis(0).getUnit().toString();
                if ("ft".equals(unit) || "feets".equals(unit))
                    units = "ft";
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Unable to determine units from crs", e);
            }
            if (units == null) {
                // fallback: meters for projected, otherwise degrees
                units = crs instanceof ProjectedCRS ? "m" : "degrees";
            }
            obj.put("unit", units);
        }

        return obj;
    }

    /**
     * Encodes a bounding box within the specified object.
     *
     * @return The object passed in.
     */
    public static JSONObj bounds(JSONObj obj, Envelope bbox) {
        Coordinate center = bbox.centre();
        obj.put("west", bbox.getMinX())
                .put("south", bbox.getMinY())
                .put("east", bbox.getMaxX())
                .put("north", bbox.getMaxY())
                .putArray("center").add(center.x).add(center.y);
        return obj;
    }

    /**
     * Decodes a bounding box within the specified object.
     *
     * @return The object passed in.
     */
    public static Envelope bounds(JSONObj obj) {
        return new Envelope(obj.doub("west"), obj.doub("east"), obj.doub("south"), obj.doub("north"));
    }
    
    public static JSONArr arr( Collection<String> strings ){
        JSONArr l = new JSONArr();
        for( String s : strings ){
            l.add(s);
        }
        return l;
    }

    /**
     * Encodes a workspace within the specified object.
     *
     * @param obj The object to encode within.
     * @param workspace The workspace to encode.
     * @param namespace The namespace corresponding to the workspace.
     * @param isDefault Flag indicating whether the workspace is the default.
     *
     * @return The object passed in.
     */
    public static JSONObj workspace(JSONObj obj, WorkspaceInfo workspace, NamespaceInfo namespace, boolean isDefault) {
        obj.put("name", workspace.getName());
        if (namespace != null) {
            obj.put("uri", namespace.getURI());
        }
        obj.put("default", isDefault);
        return obj;
    }

    /**
     * Encodes a layer within the specified object.
     *
     * @return The object passed in.
     */
    public static JSONObj layer(JSONObj obj, LayerInfo layer) {
        String wsName = layer.getResource().getNamespace().getPrefix();

        ResourceInfo r = layer.getResource();
        obj.put("name", layer.getName())
                .put("workspace", wsName)
                .put("title", layer.getTitle() != null ? layer.getTitle() : r.getTitle())
                .put("description", layer.getAbstract() != null ? layer.getAbstract() : r.getAbstract())
                .put("type", type(r));

        if (r instanceof FeatureTypeInfo) {
            FeatureTypeInfo ft = (FeatureTypeInfo) r;
            FeatureType schema;
            try {
                schema = ft.getFeatureType();
                obj.put("geometry", geometry(schema));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Error looking up schema "+ft.getNativeName(), e);
            }
        }
        else if( r instanceof CoverageInfo) {
            obj.put("geometry", "raster");
        }
        else if( r instanceof WMSInfo) {
            obj.put("geometry", "layer");
        }

        proj(obj.putObject("proj"), r.getCRS(), r.getSRS());
        bbox( obj.putObject("bbox"), r );

        return metadata(obj, layer);
    }

    static String type(ResourceInfo r)  {
        if (r instanceof CoverageInfo) {
            return "raster";
        }
        else if (r instanceof FeatureTypeInfo){
            return "vector";
        }
        else if (r instanceof WMSLayerInfo){
            return "wms";
        }
        else {
            return "resource";
        }
    }

    static String geometry(FeatureType ft) {
        GeometryDescriptor gd = ft.getGeometryDescriptor();
        if (gd == null) {
            return "Vector";
        }
        @SuppressWarnings("unchecked")
        Geometries geomType = Geometries.getForBinding((Class<? extends Geometry>) gd.getType().getBinding());
        return geomType.getName();
    }
    
    public static JSONObj bbox( JSONObj bbox, ResourceInfo r ){
        if (r.getNativeBoundingBox() != null) {
            bounds(bbox.putObject("native"), r.getNativeBoundingBox());
        }
        else {
            // check if the crs is geographic, if so use lat lon
            if (r.getCRS() instanceof GeographicCRS) {
                bounds(bbox.putObject("native"), r.getLatLonBoundingBox());
            }
        }
        bounds(bbox.putObject("lonlat"), r.getLatLonBoundingBox());       
        return bbox;
    }

    private static PrettyTime PRETTY_TIME = new PrettyTime();

    static JSONObj date(JSONObj obj, Date date) {
        String timestamp = DateUtil.formatDate( date );
        return obj.put("timestamp", timestamp).put("pretty", PRETTY_TIME.format(date));
    }

    static JSONObj metadata(JSONObj obj, Info i) {
        Date date = Metadata.created(i);
        if (date != null) {
            date(obj.putObject("created"), date);
        }

        date = Metadata.modified(i);
        if (date != null) {
            date(obj.putObject("modified"), date);
        }

        return obj;
    }

    public static Object error(JSONObj json, Throwable error) {
        if (error != null) {
            String message = null;
            JSONArr trace = new JSONArr();
            for (Throwable t : Throwables.getCausalChain(error)) {
                if (message == null && t.getMessage() != null) {
                    message = t.getMessage();
                }
                trace.add(t.toString());
            }
            json.put("message", message != null ? message : error.toString())
                .put("trace", trace);
        }
        return json;
    }
}
