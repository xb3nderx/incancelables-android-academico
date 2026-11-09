package com.incancelables.android;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Tarjetas de producto del catálogo con carga diferida de la imagen principal. */
public class ProductoAdapter extends RecyclerView.Adapter<ProductoAdapter.TarjetaHolder> {

    public interface AlSeleccionarProducto {
        void alSeleccionar(Producto producto);
    }

    public interface AlAgregarProducto {
        void alAgregar(Producto producto);
    }

    private final List<Producto> productos = new ArrayList<>();
    private final AlSeleccionarProducto alSeleccionarProducto;
    private final AlAgregarProducto alAgregarProducto;

    public ProductoAdapter(AlSeleccionarProducto alSeleccionarProducto,
                           AlAgregarProducto alAgregarProducto) {
        this.alSeleccionarProducto = alSeleccionarProducto;
        this.alAgregarProducto = alAgregarProducto;
    }

    public void actualizar(List<Producto> nuevos) {
        productos.clear();
        if (nuevos != null) {
            productos.addAll(nuevos);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TarjetaHolder onCreateViewHolder(@NonNull ViewGroup padre, int viewType) {
        View vista = LayoutInflater.from(padre.getContext())
                .inflate(R.layout.item_producto, padre, false);
        return new TarjetaHolder(vista);
    }

    @Override
    public void onBindViewHolder(@NonNull TarjetaHolder holder, int posicion) {
        Producto producto = productos.get(posicion);

        holder.imagen.setBackgroundResource(R.color.fondo_contenido);
        Producto.Imagen imagenPrincipal = producto.getImagenes().isEmpty()
                ? null : producto.getImagenes().get(0);
        CargadorImagenes.obtener(holder.itemView.getContext()).cargar(holder.imagen,
                imagenPrincipal == null ? null : imagenPrincipal.getUrl(),
                imagenPrincipal == null || imagenPrincipal.getAlt().trim().isEmpty()
                        ? producto.getNombre() : imagenPrincipal.getAlt());
        holder.nombre.setText(producto.getNombre());
        holder.descripcion.setText(producto.getDescripcion());
        holder.precio.setText(producto.getPrecioFormateado());
        int disponibilidad = producto.getDisponibilidad();
        int cantidadEnCarrito = Carrito.obtenerInstancia().getCantidad(producto.getId());
        int restante = Math.max(0, disponibilidad - cantidadEnCarrito);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (alSeleccionarProducto != null) {
                    alSeleccionarProducto.alSeleccionar(producto);
                }
            }
        });
        holder.itemView.setClickable(true);
        holder.itemView.setFocusable(true);

        if (disponibilidad <= 0) {
            holder.disponibilidad.setText(holder.itemView.getContext().getString(
                    R.string.producto_disponibilidad_agotado, 0));
            holder.disponibilidad.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.error));
            holder.disponibilidad.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            holder.disponibilidad.setAlpha(1f);
            holder.agregar.setText(R.string.carrito_sin_stock);
            holder.agregar.setEnabled(false);
        } else if (restante == 0) {
            holder.disponibilidad.setText(R.string.producto_disponibilidad_maximo);
            holder.disponibilidad.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.error));
            holder.disponibilidad.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            holder.disponibilidad.setAlpha(1f);
            holder.agregar.setText(R.string.carrito_maximo_agregado);
            holder.agregar.setEnabled(false);
        } else {
            holder.disponibilidad.setText(holder.itemView.getContext().getString(
                    R.string.producto_disponibilidad, restante));
            holder.disponibilidad.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.texto_sobre_tarjeta));
            holder.disponibilidad.setTypeface(android.graphics.Typeface.DEFAULT);
            holder.disponibilidad.setAlpha(0.85f);
            holder.agregar.setText(R.string.carrito_agregar);
            holder.agregar.setEnabled(true);
        }

        holder.agregar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (alAgregarProducto != null) {
                    alAgregarProducto.alAgregar(producto);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return productos.size();
    }

    static class TarjetaHolder extends RecyclerView.ViewHolder {

        final TextView nombre;
        final ImageView imagen;
        final TextView descripcion;
        final TextView precio;
        final TextView disponibilidad;
        final com.google.android.material.button.MaterialButton agregar;

        TarjetaHolder(@NonNull View itemView) {
            super(itemView);
            nombre = itemView.findViewById(R.id.producto_nombre);
            imagen = itemView.findViewById(R.id.producto_imagen);
            descripcion = itemView.findViewById(R.id.producto_descripcion);
            precio = itemView.findViewById(R.id.producto_precio);
            disponibilidad = itemView.findViewById(R.id.producto_disponibilidad);
            agregar = itemView.findViewById(R.id.producto_agregar);
        }
    }
}
