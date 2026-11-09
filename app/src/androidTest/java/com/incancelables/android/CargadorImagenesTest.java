package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CargadorImagenesTest {

    @Test
    public void soloAceptaHttpsYLimitaElTamanoDecodificado() {
        assertTrue(CargadorImagenes.urlHttpsValida("https://imagenes.example/producto.jpg"));
        assertFalse(CargadorImagenes.urlHttpsValida("http://imagenes.example/producto.jpg"));
        assertFalse(CargadorImagenes.urlHttpsValida("file:///sdcard/producto.jpg"));
        assertFalse(CargadorImagenes.urlHttpsValida(" "));
        assertEquals(8, CargadorImagenes.calcularMuestra(4096, 2048,
                CargadorImagenes.MAX_DIMENSION_PIXELES));
        assertEquals(1, CargadorImagenes.calcularMuestra(320, 240,
                CargadorImagenes.MAX_DIMENSION_PIXELES));
    }

    @Test
    public void urlNoHttpsConservaElPlaceholderSinIniciarDescarga() {
        Context contexto = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ImageView vista = new ImageView(contexto);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                CargadorImagenes.obtener(contexto).cargar(vista,
                        "http://imagenes.example/producto.jpg", "Imagen alternativa"));
        AtomicBoolean placeholderVisible = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                placeholderVisible.set(
                        "http://imagenes.example/producto.jpg".equals(vista.getTag())
                                && "Imagen alternativa".contentEquals(vista.getContentDescription())
                                && vista.getDrawable() != null));
        assertTrue(placeholderVisible.get());
    }

    @Test
    public void errorDeDescargaConservaElPlaceholder() throws Exception {
        Context contexto = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch intento = new CountDownLatch(1);
        AtomicReference<CargadorImagenes> referencia = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> referencia.set(
                new CargadorImagenes(contexto, url -> {
                    intento.countDown();
                    throw new IOException("fallo simulado");
                })));
        CargadorImagenes cargador = referencia.get();
        ImageView vista = new ImageView(contexto);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                cargador.cargar(vista, "https://imagenes.example/falla.jpg", "Alt"));

        assertTrue(intento.await(3, TimeUnit.SECONDS));
        AtomicBoolean placeholderVisible = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                placeholderVisible.set(vista.getScaleType() == ImageView.ScaleType.CENTER
                        && vista.getDrawable() != null));
        assertTrue(placeholderVisible.get());
    }

    @Test
    public void reutilizaImagenEnMemoriaAlVolverANavegar() throws Exception {
        Context contexto = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch descargada = new CountDownLatch(1);
        AtomicInteger llamadas = new AtomicInteger();
        AtomicReference<CargadorImagenes> referencia = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> referencia.set(
                new CargadorImagenes(contexto, url -> {
                    llamadas.incrementAndGet();
                    descargada.countDown();
                    return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                })));
        CargadorImagenes cargador = referencia.get();
        ImageView primera = new ImageView(contexto);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                cargador.cargar(primera, "https://imagenes.example/cache.jpg", "Alt"));
        assertTrue(descargada.await(3, TimeUnit.SECONDS));
        AtomicBoolean lista = new AtomicBoolean();
        for (int i = 0; i < 40 && !lista.get(); i++) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    lista.set(primera.getScaleType() == ImageView.ScaleType.CENTER_CROP));
            if (!lista.get()) Thread.sleep(50);
        }
        assertTrue(lista.get());

        ImageView segunda = new ImageView(contexto);
        AtomicBoolean segundaEnCache = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
        {
            cargador.cargar(segunda, "https://imagenes.example/cache.jpg", "Alt");
            segundaEnCache.set(segunda.getScaleType() == ImageView.ScaleType.CENTER_CROP
                    && segunda.getDrawable() != null);
        });
        assertEquals(1, llamadas.get());
        assertTrue(segundaEnCache.get());
    }
}
