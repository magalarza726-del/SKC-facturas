-- SKC Facturas 3.15.0
-- Permite a ADMIN eliminar también asientos del libro cuando usa la acción explícita
-- "Eliminar movimiento completo". Mantiene la autorización en servidor.

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
  v_now timestamptz := now();
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if not public.skc_is_admin() then raise exception 'ADMIN required'; end if;
  if v_type not in ('transactions','messages','reminders','audit','ledger') then
    raise exception 'Entity type is not eligible for cleanup';
  end if;
  if v_id = '' then raise exception 'entity id required'; end if;

  select exists(
    select 1 from public.skc_events
    where entity_type = v_type and entity_id = v_id
      and coalesce(payload->>'_deleted','false') <> 'true'
  ) into v_exists;

  insert into public.skc_events(id, entity_type, entity_id, payload, updated_at, updated_by)
  values (
    v_type || ':' || v_id, v_type, v_id,
    jsonb_build_object('id', v_id, '_deleted', true, 'deletedAt', v_now, 'deletedEntityType', v_type),
    v_now, auth.uid()
  )
  on conflict (id) do update
    set payload = excluded.payload, updated_at = excluded.updated_at, updated_by = excluded.updated_by;
  return v_exists;
end;
$$;

revoke all on function public.skc_admin_delete_entity(text,text) from public;
grant execute on function public.skc_admin_delete_entity(text,text) to authenticated;
