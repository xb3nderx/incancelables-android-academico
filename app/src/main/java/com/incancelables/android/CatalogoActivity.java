package com.incancelables.android;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Pantalla única de la Etapa 1: catálogo de productos con estados de
 * carga, vacío y error con reintento.
 */
public class CatalogoActivity extends AppCompatActivity implements Api.Oyente {

    private RecyclerView lista;
    private ProductoAdapter adaptador;
    private LinearLayout estadoContenedor;
    private TextView estadoTexto;
    private Button reintentar;
    private Button verCarrito;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_catalogo);

        SslConfianza.inicializar(getApplicationContext());

        lista = findViewById(R.id.lista_productos);
        estadoContenedor = findViewById(R.id.estado_contenedor);
        estadoTexto = findViewById(R.id.estado_texto);
        reintentar = findViewById(R.id.btn_reintentar);
        verCarrito = findViewById(R.id.btn_ver_carrito_catalogo);

        adaptador = new ProductoAdapter(new ProductoAdapter.AlSeleccionarProducto() {
            @Override
            public void alSeleccionar(Producto producto) {
                abrirDetalle(producto);
            }
        }, new ProductoAdapter.AlAgregarProducto() {
            @Override
            public void alAgregar(Producto producto) {
                agregarUnaUnidad(producto);
            }
        });
        lista.setLayoutManager(new LinearLayoutManager(this));
        lista.setAdapter(adaptador);

        reintentar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reintentar();
            }
        });
        verCarrito.setOnClickListener(v -> abrirCarrito());

        Api.registrar(this);
        sincronizarEstado();
        actualizarContadorCarrito();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adaptador != null) {
            sincronizarEstado();
            actualizarContadorCarrito();
        }
    }

    @Override
    protected void onDestroy() {
        Api.desregistrar(this);
        super.onDestroy();
    }

    @Override
    public void onProductos(List<Producto> productos) {
        if (productos.isEmpty()) {
            mostrarEstado(R.string.estado_vacio, false, false);
        } else {
            mostrarLista(productos);
        }
    }

    @Override
    public void onError(String codigoError) {
        int texto = Api.ERROR_TIMEOUT.equals(codigoError)
                ? R.string.estado_error_timeout
                : R.string.estado_error_red;
        mostrarEstado(texto, true, true);
    }

    /**
     * Reproduce el último estado conocido sin disparar una segunda
     * solicitud si ya hay una en vuelo.
     */
    private void sincronizarEstado() {
        String error = Api.getErrorGuardado();
        if (error != null) {
            onError(error);
            return;
        }

        List<Producto> productos = Api.getProductosGuardados();
        if (productos != null) {
            onProductos(productos);
            return;
        }

        if (Api.estaCargando()) {
            mostrarEstado(R.string.estado_cargando, false, false);
            return;
        }

        mostrarEstado(R.string.estado_cargando, false, false);
        Api.cargar();
    }

    private void reintentar() {
        mostrarEstado(R.string.estado_cargando, false, false);
        Api.cargar();
    }

    private void abrirDetalle(Producto producto) {
        Intent intent = new Intent(CatalogoActivity.this, DetalleProductoActivity.class);
        intent.putExtra(DetalleProductoActivity.EXTRA_ID, producto.getId());
        intent.putExtra(DetalleProductoActivity.EXTRA_NOMBRE, producto.getNombre());
        intent.putExtra(DetalleProductoActivity.EXTRA_DESCRIPCION, producto.getDescripcion());
        intent.putExtra(DetalleProductoActivity.EXTRA_PRECIO, producto.getPrecio());
        intent.putExtra(DetalleProductoActivity.EXTRA_DISPONIBILIDAD, producto.getDisponibilidad());
        java.util.List<Producto.Imagen> imagenes = producto.getImagenes();
        String[] urls = new String[imagenes.size()];
        String[] textosAlternativos = new String[imagenes.size()];
        int[] ordenes = new int[imagenes.size()];
        for (int i = 0; i < imagenes.size(); i++) {
            Producto.Imagen imagen = imagenes.get(i);
            urls[i] = imagen.getUrl();
            textosAlternativos[i] = imagen.getAlt();
            ordenes[i] = imagen.getOrden();
        }
        intent.putExtra(DetalleProductoActivity.EXTRA_IMAGENES_URL, urls);
        intent.putExtra(DetalleProductoActivity.EXTRA_IMAGENES_ALT, textosAlternativos);
        intent.putExtra(DetalleProductoActivity.EXTRA_IMAGENES_ORDEN, ordenes);
        startActivity(intent);
    }

    private void agregarUnaUnidad(Producto producto) {
        Carrito.ResultadoAgregar resultado = Carrito.obtenerInstancia().agregar(producto, 1);
        if (resultado == Carrito.ResultadoAgregar.AGREGADO) {
            int cantidad = Carrito.obtenerInstancia().getCantidad(producto.getId());
            Toast.makeText(this, getString(R.string.carrito_agregado_confirmacion,
                    producto.getNombre(), cantidad), Toast.LENGTH_SHORT).show();
            refrescarEstadoCarrito();
        } else if (resultado == Carrito.ResultadoAgregar.SIN_STOCK) {
            Toast.makeText(this, R.string.carrito_producto_agotado, Toast.LENGTH_SHORT).show();
            refrescarEstadoCarrito();
        } else if (resultado == Carrito.ResultadoAgregar.SUPERA_DISPONIBILIDAD) {
            Toast.makeText(this, R.string.carrito_stock_completo, Toast.LENGTH_SHORT).show();
            refrescarEstadoCarrito();
        }
    }

    private void refrescarEstadoCarrito() {
        adaptador.notifyDataSetChanged();
        actualizarContadorCarrito();
    }

    private void actualizarContadorCarrito() {
        int unidades = 0;
        for (Carrito.Item item : Carrito.obtenerInstancia().getItems()) {
            unidades += item.getCantidad();
        }
        verCarrito.setText(getString(R.string.catalogo_carrito_unidades, unidades));
    }

    private void abrirCarrito() {
        Intent intent = new Intent(CatalogoActivity.this, CarritoActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void mostrarLista(List<Producto> productos) {
        estadoContenedor.setVisibility(View.GONE);
        lista.setVisibility(View.VISIBLE);
        adaptador.actualizar(productos);
    }

    private void mostrarEstado(@StringRes int texto, boolean esError, boolean conReintento) {
        lista.setVisibility(View.GONE);
        estadoContenedor.setVisibility(View.VISIBLE);

        if (esError) {
            estadoContenedor.setBackgroundResource(R.drawable.bg_estado_error);
            estadoTexto.setTextColor(ContextCompat.getColor(this, R.color.error_texto));
        } else {
            estadoContenedor.setBackgroundResource(R.drawable.bg_estado);
            estadoTexto.setTextColor(ContextCompat.getColor(this, R.color.texto_claro));
        }

        estadoTexto.setText(texto);
        reintentar.setVisibility(conReintento ? View.VISIBLE : View.GONE);
    }
}
