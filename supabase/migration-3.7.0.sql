-- SKC Facturas 3.7.0
-- Limpieza permanente de facturas antiguas sin exponer service_role en Android.
-- La evidencia se elimina físicamente del proveedor de archivos y el registro remoto
-- se reduce a una tombstone mínima para evitar que un dispositivo offline lo resucite.

create or replace function public.skc_keep_newest_event()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  -- Una tombstone administrativa siempre gana, incluso si un cliente antiguo
  -- escribió un updated_at futuro por reloj desajustado.
  if coalesce(new.payload->>'_deleted','false') = 'true' then
    new.updated_by := auth.uid();
    return new;
  end if;

  -- Una entidad ya eliminada no puede revivirse desde un cliente antiguo.
  -- Una restauración futura deberá implementarse mediante una RPC administrativa explícita.
  if coalesce(old.payload->>'_deleted','false') = 'true' then
    return old;
  end if;

  if old.updated_at > new.updated_at then
    return old;
  end if;

  new.updated_by := auth.uid();
  return new;
end;
$$;

create or replace function public.skc_can_delete_invoice()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and public.skc_is_admin()
$$;

revoke all on function public.skc_can_delete_invoice() from public;
grant execute on function public.skc_can_delete_invoice() to authenticated;

create or replace function public.skc_delete_invoice(p_transaction_id text)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_exists boolean;
  v_now timestamptz := now();
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if not public.skc_is_admin() then
    raise exception 'ADMIN required';
  end if;

  if nullif(trim(p_transaction_id), '') is null then
    raise exception 'transaction id required';
  end if;

  select exists(
    select 1
    from public.skc_events
    where entity_type = 'transactions'
      and entity_id = p_transaction_id
      and coalesce(payload->>'_deleted','false') <> 'true'
  ) into v_exists;

  insert into public.skc_events(id, entity_type, entity_id, payload, updated_at, updated_by)
  values (
    'transactions:' || p_transaction_id,
    'transactions',
    p_transaction_id,
    jsonb_build_object(
      'id', p_transaction_id,
      '_deleted', true,
      'deletedAt', v_now
    ),
    v_now,
    auth.uid()
  )
  on conflict (id) do update
  set payload = excluded.payload,
      updated_at = excluded.updated_at,
      updated_by = excluded.updated_by;

  return v_exists;
end;
$$;

revoke all on function public.skc_delete_invoice(text) from public;
grant execute on function public.skc_delete_invoice(text) to authenticated;

-- Un ADMIN puede limpiar evidencias antiguas de cualquier usuario.
-- Los usuarios normales conservan permiso de borrar únicamente sus propios archivos.
drop policy if exists "skc evidence delete" on storage.objects;
create policy "skc evidence delete" on storage.objects
for delete to authenticated
using (
  bucket_id = 'skc-evidence'
  and (
    ((storage.foldername(name))[2] = auth.uid()::text)
    or public.skc_is_admin()
  )
);

-- Las tombstones son visibles para todos los usuarios autenticados. Solo contienen el ID y
-- la fecha de borrado; esto permite que un teléfono offline elimine su copia local antes de
-- intentar volver a subirla.
drop policy if exists "skc role read" on public.skc_events;
create policy "skc role read" on public.skc_events
for select to authenticated using (
  coalesce(payload->>'_deleted','false') = 'true'
  or entity_type in ('users','catalogs','appConfig','ledger')
  or public.skc_is_admin()
  or (entity_type='transactions' and (payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
);

-- Una tombstone solo se puede crear mediante la RPC administrativa. Los upserts normales
-- de la app no pueden convertir un registro en tombstone aunque un cliente sea manipulado.
drop policy if exists "skc role insert" on public.skc_events;
create policy "skc role insert" on public.skc_events
for insert to authenticated with check (
  coalesce(payload->>'_deleted','false') <> 'true'
  and (
    (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
    or (entity_type in ('catalogs','appConfig') and (public.skc_is_admin() or not public.skc_has_configured_admin()))
    or (entity_type='transactions' and payload->>'createdByUserId'=public.skc_current_profile_id())
    or (entity_type='messages' and payload->>'senderUserId'=public.skc_current_profile_id())
    or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
    or (entity_type='ledger' and payload->>'createdByUserId'=public.skc_current_profile_id())
    or (entity_type='audit' and payload->>'actorUserId'=public.skc_current_profile_id())
  )
);

drop policy if exists "skc role update" on public.skc_events;
create policy "skc role update" on public.skc_events
for update to authenticated using (
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
  or (entity_type='transactions' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (public.skc_is_admin() or payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
  or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
  or (entity_type='audit' and public.skc_is_admin())
) with check (
  coalesce(payload->>'_deleted','false') <> 'true'
  and (
    (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
    or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
    or (entity_type='transactions' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
    or (entity_type='messages' and (public.skc_is_admin() or payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
    or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
    or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
    or (entity_type='audit' and public.skc_is_admin())
  )
);
