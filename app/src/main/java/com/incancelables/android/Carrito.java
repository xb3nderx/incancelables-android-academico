package com.incancelables.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carrito temporal compartido durante la vida del proceso de la aplicación. */
public final class Carrito {

    public enum ResultadoAgregar {
        AGREGADO,
        CANTIDAD_INVALIDA,
        SIN_STOCK,
        SUPERA_DISPONIBILIDAD
    }

    private static final Carrito INSTANCIA = new Carrito();
    private final Map<Integer, Item> items = new LinkedHashMap<>();

    private Carrito() {
    }

    public static Carrito obtenerInstancia() {
        return INSTANCIA;
    }

    public synchronized ResultadoAgregar agregar(Producto producto, int cantidad) {
        if (cantidad <= 0) {
            return ResultadoAgregar.CANTIDAD_INVALIDA;
        }
        if (producto.getDisponibilidad() <= 0) {
            return ResultadoAgregar.SIN_STOCK;
        }
        Item existente = items.get(producto.getId());
        int actual = existente == null ? 0 : existente.getCantidad();
        if (cantidad > producto.getDisponibilidad() - actual) {
            return ResultadoAgregar.SUPERA_DISPONIBILIDAD;
        }
        items.put(producto.getId(), new Item(producto, actual + cantidad));
        return ResultadoAgregar.AGREGADO;
    }

    public synchronized boolean actualizarCantidad(int productoId, int cantidad) {
        Item existente = items.get(productoId);
        if (existente == null || cantidad <= 0
                || (cantidad > existente.getCantidad()
                && cantidad > existente.getProducto().getDisponibilidad())) {
            return false;
        }
        items.put(productoId, new Item(existente.getProducto(), cantidad));
        return true;
    }

    /** Actualiza datos del catálogo sin alterar cantidades, incluso si ahora exceden stock. */
    public synchronized int sincronizarProductos(List<Producto> productos) {
        if (productos == null || productos.isEmpty() || items.isEmpty()) return 0;
        Map<Integer, Producto> porId = new LinkedHashMap<>();
        for (Producto producto : productos) {
            if (producto != null) porId.put(producto.getId(), producto);
        }

        int excedidos = 0;
        for (Map.Entry<Integer, Item> entrada : items.entrySet()) {
            Item anterior = entrada.getValue();
            Producto productoCatalogo = porId.get(entrada.getKey());
            if (productoCatalogo != null) {
                Producto actualizado = new Producto(productoCatalogo.getId(),
                        productoCatalogo.getNombre(), productoCatalogo.getDescripcion(),
                        productoCatalogo.getPrecio(), productoCatalogo.getDisponibilidad(),
                        productoCatalogo.getImagenes());
                Item sincronizado = new Item(actualizado, anterior.getCantidad());
                entrada.setValue(sincronizado);
                if (sincronizado.getCantidad() > actualizado.getDisponibilidad()) {
                    excedidos++;
                }
            }
        }
        return excedidos;
    }

    public synchronized void eliminar(int productoId) {
        items.remove(productoId);
    }

    public synchronized void vaciar() {
        items.clear();
    }

    /** Reemplaza el contenido solo si todas las líneas respetan el stock. */
    public synchronized boolean reemplazar(List<Item> nuevos) {
        if (nuevos == null) {
            return false;
        }
        Map<Integer, Item> reemplazo = new LinkedHashMap<>();
        for (Item item : nuevos) {
            if (item == null || item.getCantidad() <= 0
                    || item.getCantidad() > item.getProducto().getDisponibilidad()
                    || reemplazo.containsKey(item.getProducto().getId())) {
                return false;
            }
            reemplazo.put(item.getProducto().getId(), item);
        }
        items.clear();
        items.putAll(reemplazo);
        return true;
    }

    public synchronized int getCantidad(int productoId) {
        Item item = items.get(productoId);
        return item == null ? 0 : item.getCantidad();
    }

    public synchronized List<Item> getItems() {
        return new ArrayList<>(items.values());
    }

    public synchronized long getTotalCentavos() {
        long total = 0;
        for (Item item : items.values()) {
            long subtotal = Math.multiplyExact(
                    Producto.precioEnCentavos(item.getProducto().getPrecio()), item.getCantidad());
            total = Math.addExact(total, subtotal);
        }
        return total;
    }

    public static final class Item {
        private final Producto producto;
        private final int cantidad;

        Item(Producto producto, int cantidad) {
            this.producto = producto;
            this.cantidad = cantidad;
        }

        public Producto getProducto() {
            return producto;
        }

        public int getCantidad() {
            return cantidad;
        }

        public long getSubtotalCentavos() {
            return Math.multiplyExact(
                    Producto.precioEnCentavos(producto.getPrecio()), cantidad);
        }
    }
}
