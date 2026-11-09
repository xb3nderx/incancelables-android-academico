package com.incancelables.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pruebas de formato de precio, para no depender de un dispositivo.
 */
public class ProductoTest {

    @Test
    public void precioSeFormateaComoLaWeb() {
        assertEquals("$ 25.000", Producto.formatearPrecio(25000));
        assertEquals("$ 18.000", Producto.formatearPrecio(18000));
        assertEquals("$ 1.234.567", Producto.formatearPrecio(1234567));
    }

    @Test
    public void precioSinDecimales() {
        assertEquals("$ 0", Producto.formatearPrecio(0));
        assertEquals("$ 25.000", Producto.formatearPrecio(25000.4));
        assertEquals("$ 25.001", Producto.formatearPrecio(25000.6));
    }

    @Test
    public void productoConservaSusAtributos() {
        Producto producto = new Producto(1, "Remera", "Algodón", 25000, 7);
        assertEquals(1, producto.getId());
        assertEquals("Remera", producto.getNombre());
        assertEquals("Algodón", producto.getDescripcion());
        assertEquals(25000, producto.getPrecio(), 0.0001);
        assertEquals(7, producto.getDisponibilidad());
        assertEquals("$ 25.000", producto.getPrecioFormateado());
    }
}
