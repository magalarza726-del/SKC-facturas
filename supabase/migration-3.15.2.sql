-- SKC Facturas 3.15.2
-- 1) Los mensajes borrados se eliminan físicamente de skc_events: no quedan tombstones.
-- 2) La limpieza administrativa también pasa a DELETE físico para liberar espacio real.
-- La app reconcilia los registros SYNCED ausentes del servidor en cada sincronización,
-- por lo que una eliminación remota se replica a los demás dispositivos conectados.

create or replace function public.skc_can_physical_cleanup_3152()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and public.skc_is_admin()
$$;

revoke all on function public.skc_can_physical_cleanup_3152() from public;
grant execute on function public.skc_can_physical_cleanup_3152() to authenticated;

-- Sustituye la antigua tombstone de facturas por DELETE físico. El cliente 3.15.2
-- reconcilia por ausencia del servidor al volver a sincronizar.
create or replace function public.skc_delete_invoice(p_transaction_id text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_id text := trim(coalesce(p_transaction_id, ''));
  v_exists boolean;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if not public.skc_is_admin() then raise exception 'ADMIN required'; end if;
  if v_id = '' then raise exception 'transaction id required'; end if;

  select exists(
    select 1 from public.skc_events
     where entity_type='transactions' and entity_id=v_id
  ) into v_exists;

  delete from public.skc_events
   where entity_type='transactions' and entity_id=v_id;

  return v_exists;
end;
$$;

revoke all on function public.skc_delete_invoice(text) from public;
grant execute on function public.skc_delete_invoice(text) to authenticated;

create or replace function public.skc_delete_message_physical(p_message_id text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_id text := trim(coalesce(p_message_id, ''));
  v_payload jsonb;
  v_profile_id text;
  v_is_admin boolean := false;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;
  if v_id = '' then
    raise exception 'message id required';
  end if;

  select payload
    into v_payload
    from public.skc_events
   where entity_type = 'messages'
     and entity_id = v_id
   limit 1;

  if v_payload is null then
    return false;
  end if;

  v_is_admin := public.skc_is_admin();
  v_profile_id := public.skc_current_profile_id();

  if not v_is_admin and coalesce(v_payload->>'senderUserId', '') <> coalesce(v_profile_id, '') then
    raise exception 'Only sender or ADMIN can delete this message';
  end if;

  -- Un usuario normal no puede hacer desaparecer un movimiento financiero confirmado.
  -- Ese caso debe pasar por corrección/ADMIN. La app aplica la misma regla en interfaz.
  if not v_is_admin and upper(coalesce(v_payload->>'status', '')) in ('CONFIRMADO', 'COMPLETADA') then
    raise exception 'Confirmed movement requires ADMIN correction';
  end if;

  delete from public.skc_events
   where entity_type = 'messages'
     and entity_id = v_id;

  return found;
end;
$$;

revoke all on function public.skc_delete_message_physical(text) from public;
grant execute on function public.skc_delete_message_physical(text) to authenticated;

-- Los movimientos eliminables que versiones anteriores dejaron como tombstones ya no son
-- necesarios: la reconciliación por ausencia del cliente 3.15.2 propaga el borrado.
delete from public.skc_events
 where entity_type in ('transactions','messages','reminders','audit','ledger')
   and coalesce(payload->>'_deleted', 'false') = 'true';

-- A partir de 3.15.2 "Eliminar movimiento" libera espacio real. No se crea una fila
-- _deleted. Se conserva la autorización exclusivamente del lado servidor.
create or replace function public.skc_admin_delete_entity(
  p_entity_type text,
  p_entity_id text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_type text := trim(coalesce(p_entity_type, ''));
  v_id text := trim(coalesce(p_entity_id, ''));
  v_exists boolean;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if not public.skc_is_admin() then raise exception 'ADMIN required'; end if;
  if v_type not in ('transactions','messages','reminders','audit','ledger') then
    raise exception 'Entity type is not eligible for cleanup';
  end if;
  if v_id = '' then raise exception 'entity id required'; end if;

  select exists(
    select 1 from public.skc_events
     where entity_type = v_type
       and entity_id = v_id
  ) into v_exists;

  delete from public.skc_events
   where entity_type = v_type
     and entity_id = v_id;

  return v_exists;
end;
$$;

revoke all on function public.skc_admin_delete_entity(text,text) from public;
grant execute on function public.skc_admin_delete_entity(text,text) to authenticated;
