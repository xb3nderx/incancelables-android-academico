package com.incancelables.android;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class CarritoActivity extends AppCompatActivity
        implements CarritoAdapter.Acciones, Api.Oyente {

    private Carrito carrito;
    private CarritoAdapter adaptador;
    private RecyclerView lista;
    private TextView vacio;
    private TextView unidades;
    private TextView total;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carrito);

        Toolbar toolbar = findViewById(R.id.carrito_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.carrito_titulo);
        }

        carrito = Carrito.obtenerInstancia();
        Api.registrar(this);
        lista = findViewById(R.id.carrito_lista);
        vacio = findViewById(R.id.carrito_vacio);
        unidades = findViewById(R.id.carrito_unidades);
        total = findViewById(R.id.carrito_total);
        adaptador = new CarritoAdapter(this);
        lista.setLayoutManager(new LinearLayoutManager(this));
        lista.setAdapter(adaptador);

        findViewById(R.id.carrito_volver_catalogo).setOnClickListener(v -> {
            Intent intent = new Intent(CarritoActivity.this, CatalogoActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        findViewById(R.id.carrito_continuar_compra).setOnClickListener(v -> {
            if (carrito.getItems().isEmpty()) {
                return;
            }
            startActivity(new Intent(CarritoActivity.this, CheckoutActivity.class));
        });
        actualizarVista();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (carrito != null) {
            actualizarVista();
        }
    }

    @Override
    protected void onDestroy() {
        Api.desregistrar(this);
        super.onDestroy();
    }

    @Override
    public void onProductos(java.util.List<Producto> productos) {
        actualizarVista();
    }

    @Override
    public void onError(String codigoError) {
        // Conserva la vista actual; el error y reintento del GET se muestran en el catálogo.
    }

    private void actualizarVista() {
        java.util.List<Carrito.Item> items = carrito.getItems();
        boolean estaVacio = items.isEmpty();
        vacio.setVisibility(estaVacio ? View.VISIBLE : View.GONE);
        lista.setVisibility(estaVacio ? View.GONE : View.VISIBLE);
        findViewById(R.id.carrito_continuar_compra)
                .setVisibility(estaVacio ? View.GONE : View.VISIBLE);
        unidades.setVisibility(estaVacio ? View.GONE : View.VISIBLE);
        total.setVisibility(estaVacio ? View.GONE : View.VISIBLE);
        adaptador.actualizar(items);
        if (!estaVacio) {
            int cantidadTotal = 0;
            for (Carrito.Item item : items) {
                cantidadTotal += item.getCantidad();
            }
            unidades.setText(getResources().getQuantityString(
                    R.plurals.carrito_unidades, cantidadTotal, cantidadTotal));
            total.setText(getString(R.string.carrito_total,
                    Producto.formatearCentavos(carrito.getTotalCentavos())));
        }
    }

    @Override
    public void cambiarCantidad(int productoId, int cantidad) {
        if (cantidad == 0) {
            carrito.eliminar(productoId);
        } else if (!carrito.actualizarCantidad(productoId, cantidad)) {
            Toast.makeText(this, R.string.carrito_limite_stock, Toast.LENGTH_SHORT).show();
        }
        actualizarVista();
    }

    @Override
    public void eliminar(int productoId) {
        carrito.eliminar(productoId);
        actualizarVista();
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
