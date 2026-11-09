package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProductoImagenTest {

    @Test
    public void imagenesSeOrdenanPorElOrdenDelBackend() throws Exception {
        Producto producto = Producto.desdeJson(new JSONObject(
                "{\"id\":1,\"nombre\":\"Remera\",\"precio\":1000,"
                        + "\"disponibilidad\":4,\"imagenes\":["
                        + "{\"url\":\"https://img.test/3.jpg\",\"alt\":\"tercera\",\"orden\":3},"
                        + "{\"url\":\"https://img.test/1.jpg\",\"alt\":\"primera\",\"orden\":1},"
                        + "{\"url\":\"https://img.test/2.jpg\",\"alt\":\"segunda\",\"orden\":2}]}"));

        assertEquals(3, producto.getImagenes().size());
        assertEquals("https://img.test/1.jpg", producto.getImagenes().get(0).getUrl());
        assertEquals("primera", producto.getImagenes().get(0).getAlt());
        assertEquals(2, producto.getImagenes().get(1).getOrden());
        assertEquals("https://img.test/3.jpg", producto.getImagenes().get(2).getUrl());
    }

    @Test
    public void productoSinImagenesMantieneListaVacia() throws Exception {
        Producto producto = Producto.desdeJson(new JSONObject(
                "{\"id\":2,\"nombre\":\"Gorra\",\"precio\":500,\"disponibilidad\":1}"));
        assertTrue(producto.getImagenes().isEmpty());
    }
}
