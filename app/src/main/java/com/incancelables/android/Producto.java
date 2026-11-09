package com.incancelables.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Modelo mínimo de producto tal como lo devuelve GET /productos.
 */
public final class Producto {

    private final int id;
    private final String nombre;
    private final String descripcion;
    private final double precio;
    private final int disponibilidad;
    private final List<Imagen> imagenes;

    public Producto(int id, String nombre, String descripcion, double precio, int disponibilidad) {
        this(id, nombre, descripcion, precio, disponibilidad, Collections.<Imagen>emptyList());
    }

    public Producto(int id, String nombre, String descripcion, double precio, int disponibilidad,
                    List<Imagen> imagenes) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precio = precio;
        this.disponibilidad = disponibilidad;
        List<Imagen> copia = new ArrayList<>();
        if (imagenes != null) {
            for (Imagen imagen : imagenes) {
                if (imagen != null && imagen.getUrl() != null
                        && !imagen.getUrl().trim().isEmpty()) {
                    copia.add(imagen);
                }
            }
        }
        Collections.sort(copia, new java.util.Comparator<Imagen>() {
            @Override
            public int compare(Imagen izquierda, Imagen derecha) {
                return Integer.compare(izquierda.getOrden(), derecha.getOrden());
            }
        });
        this.imagenes = Collections.unmodifiableList(copia);
    }

    public static Producto desdeJson(JSONObject json) throws JSONException {
        return new Producto(
                json.getInt("id"),
                json.getString("nombre"),
                json.optString("descripcion", ""),
                json.getDouble("precio"),
                json.getInt("disponibilidad"),
                imagenesDesdeJson(json.optJSONArray("imagenes")));
    }

    private static List<Imagen> imagenesDesdeJson(org.json.JSONArray arreglo) {
        List<Imagen> imagenes = new ArrayList<>();
        if (arreglo == null) return imagenes;
        for (int i = 0; i < arreglo.length(); i++) {
            JSONObject objeto = arreglo.optJSONObject(i);
            if (objeto == null) continue;
            String url = objeto.optString("url", "");
            if (url.trim().isEmpty()) continue;
            imagenes.add(new Imagen(url, objeto.optString("alt", ""),
                    objeto.optInt("orden", i + 1)));
        }
        return imagenes;
    }

    public int getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public double getPrecio() {
        return precio;
    }

    public int getDisponibilidad() {
        return disponibilidad;
    }

    public List<Imagen> getImagenes() {
        return imagenes;
    }

    public static final class Imagen {
        private final String url;
        private final String alt;
        private final int orden;

        public Imagen(String url, String alt, int orden) {
            this.url = url;
            this.alt = alt == null ? "" : alt;
            this.orden = orden;
        }

        public String getUrl() {
            return url;
        }

        public String getAlt() {
            return alt;
        }

        public int getOrden() {
            return orden;
        }
    }

    /**
     * Replica el formato de la web:
     * Intl.NumberFormat("es-AR", { style: "currency", currency: "ARS",
     * minimumFractionDigits: 0, maximumFractionDigits: 0 }) => "$ 25.000"
     */
    public static String formatearPrecio(double valor) {
        java.text.DecimalFormat formato =
                (java.text.DecimalFormat) java.text.NumberFormat.getIntegerInstance(new Locale("es", "AR"));
        java.text.DecimalFormatSymbols simbolos = formato.getDecimalFormatSymbols();
        simbolos.setGroupingSeparator('.');
        formato.setDecimalFormatSymbols(simbolos);
        formato.setGroupingUsed(true);
        formato.setMaximumFractionDigits(0);
        formato.setMinimumFractionDigits(0);
        return "$ " + formato.format(Math.round(valor));
    }

    public static long precioEnCentavos(double valor) {
        return BigDecimal.valueOf(valor).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String formatearCentavos(long centavos) {
        java.text.DecimalFormatSymbols simbolos =
                new java.text.DecimalFormatSymbols(new Locale("es", "AR"));
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        java.text.DecimalFormat formato = new java.text.DecimalFormat("#,##0.##", simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return "$ " + formato.format(BigDecimal.valueOf(centavos, 2));
    }

    public String getPrecioFormateado() {
        return formatearPrecio(precio);
    }
}
