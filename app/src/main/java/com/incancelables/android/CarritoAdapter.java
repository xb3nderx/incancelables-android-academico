package com.incancelables.android;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class CarritoAdapter extends RecyclerView.Adapter<CarritoAdapter.ItemHolder> {

    public interface Acciones {
        void cambiarCantidad(int productoId, int cantidad);
        void eliminar(int productoId);
    }

    private final List<Carrito.Item> items = new ArrayList<>();
    private final Acciones acciones;

    public CarritoAdapter(Acciones acciones) {
        this.acciones = acciones;
    }

    public void actualizar(List<Carrito.Item> nuevos) {
        items.clear();
        items.addAll(nuevos);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View vista = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_carrito, parent, false);
        return new ItemHolder(vista);
    }

    @Override
    public void onBindViewHolder(@NonNull ItemHolder holder, int position) {
        Carrito.Item item = items.get(position);
        Producto producto = item.getProducto();
        int id = producto.getId();
        int cantidad = item.getCantidad();

        holder.nombre.setText(producto.getNombre());
        holder.precioUnitario.setText(holder.itemView.getContext().getString(
                R.string.carrito_precio_unitario, producto.getPrecioFormateado()));
        holder.cantidad.setText(holder.itemView.getContext().getString(
                R.string.carrito_cantidad_valor, cantidad));
        holder.subtotal.setText(holder.itemView.getContext().getString(
                R.string.carrito_subtotal,
                Producto.formatearCentavos(item.getSubtotalCentavos())));
        holder.disminuir.setEnabled(cantidad > 0);
        holder.aumentar.setEnabled(cantidad < producto.getDisponibilidad());

        holder.disminuir.setOnClickListener(v -> acciones.cambiarCantidad(id, cantidad - 1));
        holder.aumentar.setOnClickListener(v -> acciones.cambiarCantidad(id, cantidad + 1));
        holder.eliminar.setOnClickListener(v -> acciones.eliminar(id));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        final TextView nombre;
        final TextView precioUnitario;
        final TextView cantidad;
        final TextView subtotal;
        final MaterialButton disminuir;
        final MaterialButton aumentar;
        final MaterialButton eliminar;

        ItemHolder(@NonNull View itemView) {
            super(itemView);
            nombre = itemView.findViewById(R.id.carrito_item_nombre);
            precioUnitario = itemView.findViewById(R.id.carrito_item_precio);
            cantidad = itemView.findViewById(R.id.carrito_item_cantidad);
            subtotal = itemView.findViewById(R.id.carrito_item_subtotal);
            disminuir = itemView.findViewById(R.id.carrito_item_disminuir);
            aumentar = itemView.findViewById(R.id.carrito_item_aumentar);
            eliminar = itemView.findViewById(R.id.carrito_item_eliminar);
        }
    }
}
