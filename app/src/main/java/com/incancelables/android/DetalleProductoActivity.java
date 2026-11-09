package com.incancelables.android;

import android.os.Bundle;
import android.content.Intent;
import android.view.GestureDetector;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class DetalleProductoActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "com.incancelables.android.extra.ID";
    public static final String EXTRA_NOMBRE = "com.incancelables.android.extra.NOMBRE";
    public static final String EXTRA_DESCRIPCION = "com.incancelables.android.extra.DESCRIPCION";
    public static final String EXTRA_PRECIO = "com.incancelables.android.extra.PRECIO";
    public static final String EXTRA_DISPONIBILIDAD = "com.incancelables.android.extra.DISPONIBILIDAD";
    public static final String EXTRA_IMAGENES_URL = "com.incancelables.android.extra.IMAGENES_URL";
    public static final String EXTRA_IMAGENES_ALT = "com.incancelables.android.extra.IMAGENES_ALT";
    public static final String EXTRA_IMAGENES_ORDEN = "com.incancelables.android.extra.IMAGENES_ORDEN";
    private static final String ESTADO_IMAGEN_SELECCIONADA = "detalle_imagen_seleccionada";

    private Producto producto;
    private Button botonAgregar;
    private TextView estadoCarrito;
    private TextView disponibilidadVista;
    private ViewFlipper galeria;
    private TextView indicadorGaleria;
    private CargadorImagenes cargadorImagenes;
    private int imagenSeleccionada;
    private String firmaGaleria;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detalle_producto);

        Toolbar toolbar = findViewById(R.id.detalle_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.detalle_titulo);
        }

        TextView error = findViewById(R.id.detalle_error);
        View contenido = findViewById(R.id.detalle_contenido);
        Bundle extras = getIntent().getExtras();
        if (!extrasValidos(extras)) {
            contenido.setVisibility(View.GONE);
            error.setVisibility(View.VISIBLE);
            return;
        }

        int id = extras.getInt(EXTRA_ID);
        String nombre = extras.getString(EXTRA_NOMBRE);
        Object descripcionExtra = extras.get(EXTRA_DESCRIPCION);
        String descripcion = descripcionExtra instanceof String ? (String) descripcionExtra : "";
        double precio = extras.getDouble(EXTRA_PRECIO);
        int disponibilidad = extras.getInt(EXTRA_DISPONIBILIDAD);
        List<Producto.Imagen> imagenes = imagenesDesdeExtras(getIntent());
        producto = new Producto(id, nombre, descripcion, precio, disponibilidad, imagenes);
        imagenSeleccionada = savedInstanceState == null
                ? 0 : savedInstanceState.getInt(ESTADO_IMAGEN_SELECCIONADA, 0);
        galeria = findViewById(R.id.detalle_galeria_imagenes);
        indicadorGaleria = findViewById(R.id.detalle_imagen_indicador);
        cargadorImagenes = CargadorImagenes.obtener(getApplicationContext());
        configurarGaleria(producto.getImagenes());
        configurarGestosGaleria();

        ((TextView) findViewById(R.id.detalle_nombre)).setText(nombre);
        TextView descripcionVista = findViewById(R.id.detalle_descripcion);
        if (descripcion.trim().isEmpty()) {
            descripcionVista.setVisibility(View.GONE);
        } else {
            descripcionVista.setText(descripcion);
        }
        ((TextView) findViewById(R.id.detalle_precio)).setText(Producto.formatearPrecio(precio));
        disponibilidadVista = findViewById(R.id.detalle_disponibilidad);

        botonAgregar = findViewById(R.id.btn_agregar_carrito);
        estadoCarrito = findViewById(R.id.detalle_estado_carrito);
        botonAgregar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Carrito.ResultadoAgregar resultado = Carrito.obtenerInstancia()
                        .agregar(producto, 1);
                if (resultado == Carrito.ResultadoAgregar.AGREGADO) {
                    Toast.makeText(DetalleProductoActivity.this,
                            getString(R.string.carrito_agregado_confirmacion,
                                    producto.getNombre(),
                                    Carrito.obtenerInstancia().getCantidad(producto.getId())),
                            Toast.LENGTH_SHORT).show();
                } else if (resultado == Carrito.ResultadoAgregar.SUPERA_DISPONIBILIDAD) {
                    Toast.makeText(DetalleProductoActivity.this,
                            R.string.carrito_stock_completo, Toast.LENGTH_SHORT).show();
                } else if (resultado == Carrito.ResultadoAgregar.SIN_STOCK) {
                    Toast.makeText(DetalleProductoActivity.this,
                            R.string.carrito_producto_agotado, Toast.LENGTH_SHORT).show();
                }
                actualizarDisponibilidadCarrito();
            }
        });
        findViewById(R.id.btn_ver_carrito).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(DetalleProductoActivity.this, CarritoActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });
        actualizarDisponibilidadCarrito();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (producto != null) {
            sincronizarProductoDesdeCache();
            actualizarDisponibilidadCarrito();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (galeria != null) {
            outState.putInt(ESTADO_IMAGEN_SELECCIONADA, galeria.getDisplayedChild());
        }
    }

    private List<Producto.Imagen> imagenesDesdeExtras(Intent intent) {
        String[] urls = intent.getStringArrayExtra(EXTRA_IMAGENES_URL);
        String[] alts = intent.getStringArrayExtra(EXTRA_IMAGENES_ALT);
        int[] ordenes = intent.getIntArrayExtra(EXTRA_IMAGENES_ORDEN);
        List<Producto.Imagen> imagenes = new ArrayList<>();
        if (urls == null) return imagenes;
        for (int i = 0; i < urls.length; i++) {
            imagenes.add(new Producto.Imagen(urls[i],
                    alts != null && i < alts.length ? alts[i] : "",
                    ordenes != null && i < ordenes.length ? ordenes[i] : i + 1));
        }
        return imagenes;
    }

    private void configurarGaleria(List<Producto.Imagen> imagenes) {
        String firma = firmaDeImagenes(imagenes);
        if (firma.equals(firmaGaleria) && galeria.getChildCount() > 0) {
            imagenSeleccionada = Math.min(imagenSeleccionada, galeria.getChildCount() - 1);
            galeria.setDisplayedChild(imagenSeleccionada);
            actualizarIndicadorGaleria();
            return;
        }
        firmaGaleria = firma;
        galeria.removeAllViews();
        if (imagenes == null || imagenes.isEmpty()) {
            ImageView placeholder = crearVistaImagen(producto.getNombre());
            galeria.addView(placeholder);
            imagenSeleccionada = 0;
        } else {
            for (Producto.Imagen imagen : imagenes) {
                String alt = imagen.getAlt().trim().isEmpty()
                        ? producto.getNombre() : imagen.getAlt();
                ImageView vista = crearVistaImagen(alt);
                cargadorImagenes.cargar(vista, imagen.getUrl(), alt);
                galeria.addView(vista);
            }
            imagenSeleccionada = Math.min(imagenSeleccionada, imagenes.size() - 1);
        }
        galeria.setDisplayedChild(imagenSeleccionada);
        actualizarIndicadorGaleria();
    }

    private ImageView crearVistaImagen(String descripcion) {
        ImageView vista = new ImageView(this);
        vista.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vista.setBackgroundResource(R.color.fondo_contenido);
        vista.setImageResource(R.drawable.imagen_producto_placeholder);
        vista.setScaleType(ImageView.ScaleType.CENTER);
        vista.setContentDescription(descripcion);
        vista.setClickable(false);
        return vista;
    }

    private void configurarGestosGaleria() {
        GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent evento) {
                        return true;
                    }

                    @Override
                    public boolean onFling(android.view.MotionEvent inicio,
                                           android.view.MotionEvent fin,
                                           float velocidadX, float velocidadY) {
                        float desplazamientoX = fin.getX() - inicio.getX();
                        float desplazamientoY = fin.getY() - inicio.getY();
                        if (Math.abs(desplazamientoX) < 50
                                || Math.abs(desplazamientoX) <= Math.abs(desplazamientoY)) {
                            return false;
                        }
                        mostrarImagen(imagenSeleccionada + (desplazamientoX < 0 ? 1 : -1));
                        return true;
                    }
                });
        galeria.setOnTouchListener((vista, evento) -> detector.onTouchEvent(evento));
    }

    private void mostrarImagen(int indice) {
        int total = galeria.getChildCount();
        if (total <= 1) return;
        imagenSeleccionada = (indice + total) % total;
        galeria.setDisplayedChild(imagenSeleccionada);
        actualizarIndicadorGaleria();
    }

    private void actualizarIndicadorGaleria() {
        int total = galeria.getChildCount();
        if (total <= 1) {
            indicadorGaleria.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder puntos = new SpannableStringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) puntos.append("   ");
            int inicio = puntos.length();
            puntos.append(i == imagenSeleccionada ? "●" : "○");
            puntos.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this,
                            i == imagenSeleccionada ? R.color.acento : R.color.borde)),
                    inicio, puntos.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        indicadorGaleria.setText(puntos);
        indicadorGaleria.setTextColor(ContextCompat.getColor(this, R.color.texto_secundario));
        indicadorGaleria.setVisibility(View.VISIBLE);
        List<Producto.Imagen> imagenes = producto.getImagenes();
        String alt = imagenes.isEmpty() ? producto.getNombre()
                : imagenes.get(imagenSeleccionada).getAlt();
        indicadorGaleria.setContentDescription(getString(R.string.detalle_imagen_seleccionada,
                imagenSeleccionada + 1, total, alt == null || alt.trim().isEmpty()
                        ? producto.getNombre() : alt));
    }

    private static String firmaDeImagenes(List<Producto.Imagen> imagenes) {
        if (imagenes == null || imagenes.isEmpty()) return "";
        StringBuilder firma = new StringBuilder();
        for (Producto.Imagen imagen : imagenes) {
            firma.append(imagen.getOrden()).append('|').append(imagen.getUrl()).append('|')
                    .append(imagen.getAlt()).append('\n');
        }
        return firma.toString();
    }

    private void sincronizarProductoDesdeCache() {
        java.util.List<Producto> productos = Api.getProductosGuardados();
        if (productos == null) return;
        for (Producto actualizado : productos) {
            if (actualizado.getId() != producto.getId()) continue;
            producto = actualizado;
            ((TextView) findViewById(R.id.detalle_nombre)).setText(actualizado.getNombre());
            TextView descripcion = findViewById(R.id.detalle_descripcion);
            if (actualizado.getDescripcion().trim().isEmpty()) {
                descripcion.setVisibility(View.GONE);
            } else {
                descripcion.setVisibility(View.VISIBLE);
                descripcion.setText(actualizado.getDescripcion());
            }
            ((TextView) findViewById(R.id.detalle_precio))
                    .setText(Producto.formatearPrecio(actualizado.getPrecio()));
            configurarGaleria(actualizado.getImagenes());
            return;
        }
    }

    private void actualizarDisponibilidadCarrito() {
        int enCarrito = Carrito.obtenerInstancia().getCantidad(producto.getId());
        int restante = Math.max(0, producto.getDisponibilidad() - enCarrito);
        boolean habilitado = producto.getDisponibilidad() > 0 && restante > 0;
        botonAgregar.setEnabled(habilitado);
        if (producto.getDisponibilidad() <= 0) {
            disponibilidadVista.setText(getString(R.string.producto_disponibilidad_agotado, 0));
            disponibilidadVista.setTextColor(ContextCompat.getColor(this, R.color.error));
            disponibilidadVista.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            estadoCarrito.setText(R.string.carrito_producto_agotado);
        } else if (!habilitado) {
            disponibilidadVista.setText(R.string.producto_disponibilidad_maximo);
            disponibilidadVista.setTextColor(ContextCompat.getColor(this, R.color.error));
            disponibilidadVista.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            estadoCarrito.setText(R.string.carrito_stock_completo);
        } else {
            disponibilidadVista.setText(getString(R.string.producto_disponibilidad, restante));
            disponibilidadVista.setTextColor(ContextCompat.getColor(this, R.color.texto_sobre_tarjeta));
            disponibilidadVista.setTypeface(android.graphics.Typeface.DEFAULT);
            estadoCarrito.setText(getString(R.string.carrito_stock_restante, restante));
        }
    }

    private boolean extrasValidos(Bundle extras) {
        if (extras == null || !extras.containsKey(EXTRA_ID)
                || !extras.containsKey(EXTRA_NOMBRE) || !extras.containsKey(EXTRA_PRECIO)
                || !extras.containsKey(EXTRA_DISPONIBILIDAD)) {
            return false;
        }
        Object id = extras.get(EXTRA_ID);
        Object nombreExtra = extras.get(EXTRA_NOMBRE);
        Object precioExtra = extras.get(EXTRA_PRECIO);
        Object disponibilidad = extras.get(EXTRA_DISPONIBILIDAD);
        if (!(id instanceof Integer) || !(nombreExtra instanceof String)
                || !(precioExtra instanceof Double) || !(disponibilidad instanceof Integer)) {
            return false;
        }
        String nombre = (String) nombreExtra;
        double precio = (Double) precioExtra;
        return nombre != null && !nombre.trim().isEmpty()
                && !Double.isNaN(precio) && !Double.isInfinite(precio);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
