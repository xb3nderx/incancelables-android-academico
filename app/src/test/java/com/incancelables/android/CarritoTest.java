package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarritoTest {

    @Test
    public void acumulaCantidadesRespetaStockYCalculaEnCentavos() {
        Carrito carrito = Carrito.obtenerInstancia();
        Producto remera = new Producto(9001, "Remera", "", 1234.56, 2);
        Producto accesorio = new Producto(9002, "Accesorio", "", 0.10, 3);
        carrito.eliminar(remera.getId());
        carrito.eliminar(accesorio.getId());

        assertEquals(Carrito.ResultadoAgregar.AGREGADO, carrito.agregar(remera, 1));
        assertEquals(Carrito.ResultadoAgregar.AGREGADO, carrito.agregar(remera, 1));
        assertEquals(Carrito.ResultadoAgregar.SUPERA_DISPONIBILIDAD, carrito.agregar(remera, 1));
        assertEquals(Carrito.ResultadoAgregar.AGREGADO, carrito.agregar(accesorio, 3));
        assertEquals(2, carrito.getCantidad(remera.getId()));
        assertEquals(246942L, carrito.getTotalCentavos());

        assertFalse(carrito.actualizarCantidad(remera.getId(), 3));
        assertTrue(carrito.actualizarCantidad(remera.getId(), 1));
        assertEquals(123486L, carrito.getTotalCentavos());
        assertEquals("$ 1.234,56", Producto.formatearCentavos(123456L));

        carrito.eliminar(remera.getId());
        carrito.eliminar(accesorio.getId());
        assertEquals(0L, carrito.getTotalCentavos());
    }

    @Test
    public void sincronizaPrecioYDisponibilidadSinReducirCantidadExcedida() {
        Carrito carrito = Carrito.obtenerInstancia();
        int id = 9003;
        carrito.eliminar(id);
        Producto anterior = new Producto(id, "Producto", "Descripción", 1000, 6);
        assertEquals(Carrito.ResultadoAgregar.AGREGADO, carrito.agregar(anterior, 4));

        int excedidos = carrito.sincronizarProductos(java.util.Collections.singletonList(
                new Producto(id, "Producto actualizado", "Nueva descripción", 1200, 2)));

        assertEquals(1, excedidos);
        assertEquals(4, carrito.getCantidad(id));
        assertEquals("Producto actualizado", carrito.getItems().get(0).getProducto().getNombre());
        assertEquals(1200, carrito.getItems().get(0).getProducto().getPrecio(), 0.0);
        assertEquals(2, carrito.getItems().get(0).getProducto().getDisponibilidad());

        assertTrue(carrito.actualizarCantidad(id, 3));
        assertFalse(carrito.actualizarCantidad(id, 4));
        carrito.eliminar(id);
    }
}
