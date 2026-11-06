-- ============================================================================
-- SKC FACTURAS MOBILE - ACTUALIZACION ACUMULATIVA DE SUPABASE HASTA 3.10.1
-- ============================================================================
-- Uso:
--   1) Supabase -> SQL Editor -> New query
--   2) Pegar TODO este archivo
--   3) Run
--
-- Objetivo:
--   - Poner una instalacion existente de SKC Facturas al estado SQL esperado por 3.10.1.
--   - Incluye los cambios acumulados relevantes de 3.2.7, 3.2.9, 3.5.2,
--     3.7.0 y 3.10.0.
--   - Es seguro volver a ejecutarlo: usa CREATE OR REPLACE, DROP POLICY IF EXISTS
--     e INSERT ... ON CONFLICT donde corresponde.
--
-- IMPORTANTE:
--   - Este archivo NO crea el esquema base desde cero. Se asume que ya existe
--     public.skc_events y las funciones base de autenticacion/roles.
--   - No borra facturas ni archivos al ejecutarse. Solo instala funciones,
--     permisos, buckets y politicas necesarias para que la app pueda hacerlo.
--   - Cloudflare R2 usa una Edge Function aparte; esa parte no puede instalarse
--     mediante SQL.
-- ============================================================================

begin;

-- --------------------------------------------------------------------------
-- 0. Comprobaciones minimas del esquema base
-- --------------------------------------------------------------------------
do $$
begin
  if to_regclass('public.skc_events') is null then
    raise exception 'Falta public.skc_events. Ejecuta primero el esquema base de SKC Facturas.';
  end if;

  if to_regprocedure('public.skc_is_admin()') is null then
    raise exception 'Falta public.skc_is_admin(). Ejecuta primero el esquema base de SKC Facturas.';
  end if;

  if to_regprocedure('public.skc_current_profile_id()') is null then
    raise exception 'Falta public.skc_current_profile_id(). Ejecuta primero el esquema base de SKC Facturas.';
  end if;

  if to_regprocedure('public.skc_has_configured_admin()') is null then
    raise exception 'Falta public.skc_has_configured_admin(). Ejecuta primero el esquema base de SKC Facturas.';
  end if;
end
$$;

alter table public.skc_events enable row level security;

-- --------------------------------------------------------------------------
-- 1. Compatibilidad de perfil ADMIN historico (3.2.7)
-- --------------------------------------------------------------------------
update public.skc_events
set payload = payload || '{"role":"ADMIN","transferEnabled":true}'::jsonb,
    updated_at = now()
where entity_type = 'users'
  and entity_id = 'administracion';

-- --------------------------------------------------------------------------
-- 2. Buckets de Supabase Storage y tipos permitidos (3.2.9 + estado final)
-- --------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'skc-evidence',
  'skc-evidence',
  false,
  52428800,
  array[
    'application/pdf',
    'image/jpeg',
    'image/png',
    'image/webp',
    'image/gif',
    'image/heic',
    'image/heif',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/zip',
    'application/octet-stream'
  ]
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- Bucket historico de plantillas; se conserva por compatibilidad.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'skc-excel-templates',
  'skc-excel-templates',
  false,
  10485760,
  array[
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/zip',
    'application/octet-stream'
  ]
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

-- --------------------------------------------------------------------------
-- 3. Tombstones y proteccion contra resurreccion de datos (3.7.0)
-- --------------------------------------------------------------------------
create or replace function public.skc_keep_newest_event()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  -- Una tombstone administrativa siempre gana.
  if coalesce(new.payload->>'_deleted','false') = 'true' then
    new.updated_by := auth.uid();
    return new;
  end if;

  -- Un registro ya eliminado no puede revivirse por un cliente antiguo/offline.
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

-- Garantiza que el trigger exista incluso si la instalacion proviene de una
-- version antigua del esquema base.
drop trigger if exists skc_keep_newest_event_trigger on public.skc_events;
create trigger skc_keep_newest_event_trigger
before update on public.skc_events
for each row execute function public.skc_keep_newest_event();

-- --------------------------------------------------------------------------
-- 4. RLS final de public.skc_events (3.5.2 + 3.7.0)
-- --------------------------------------------------------------------------
drop policy if exists "skc authenticated read" on public.skc_events;
drop policy if exists "skc authenticated insert" on public.skc_events;
drop policy if exists "skc authenticated update" on public.skc_events;
drop policy if exists "skc authenticated delete" on public.skc_events;
drop policy if exists "skc role read" on public.skc_events;
drop policy if exists "skc role insert" on public.skc_events;
drop policy if exists "skc role update" on public.skc_events;

create policy "skc role read" on public.skc_events
for select to authenticated using (
  coalesce(payload->>'_deleted','false') = 'true'
  or entity_type in ('users','catalogs','appConfig','ledger')
  or public.skc_is_admin()
  or (
    entity_type='transactions'
    and (
      payload->>'createdByUserId'=public.skc_current_profile_id()
      or payload->>'accountUserId'=public.skc_current_profile_id()
    )
  )
  or (
    entity_type='messages'
    and (
      payload->>'senderUserId'=public.skc_current_profile_id()
      or payload->>'recipientUserId'=public.skc_current_profile_id()
    )
  )
  or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
);

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

create policy "skc role update" on public.skc_events
for update to authenticated using (
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
  or (
    entity_type='transactions'
    and (
      public.skc_is_admin()
      or payload->>'createdByUserId'=public.skc_current_profile_id()
      or payload->>'accountUserId'=public.skc_current_profile_id()
    )
  )
  or (
    entity_type='messages'
    and (
      public.skc_is_admin()
      or payload->>'senderUserId'=public.skc_current_profile_id()
      or payload->>'recipientUserId'=public.skc_current_profile_id()
    )
  )
  or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
  or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
  or (entity_type='audit' and public.skc_is_admin())
)
with check (
  coalesce(payload->>'_deleted','false') <> 'true'
  and (
    (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
    or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
    or (
      entity_type='transactions'
      and (
        public.skc_is_admin()
        or payload->>'createdByUserId'=public.skc_current_profile_id()
        or payload->>'accountUserId'=public.skc_current_profile_id()
      )
    )
    or (
      entity_type='messages'
      and (
        public.skc_is_admin()
        or payload->>'senderUserId'=public.skc_current_profile_id()
        or payload->>'recipientUserId'=public.skc_current_profile_id()
      )
    )
    or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
    or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
    or (entity_type='audit' and public.skc_is_admin())
  )
);

-- --------------------------------------------------------------------------
-- 5. Politicas finales de Supabase Storage (3.2.9 + 3.7.0 + 3.10.0)
-- --------------------------------------------------------------------------
drop policy if exists "skc evidence read" on storage.objects;
drop policy if exists "skc evidence insert" on storage.objects;
drop policy if exists "skc evidence update" on storage.objects;
drop policy if exists "skc evidence delete" on storage.objects;

-- ADMIN puede inventariar todo el bucket para detectar huerfanos.
-- Usuario normal: sus propios archivos o evidencia referenciada por una entidad visible.
create policy "skc evidence read" on storage.objects
for select to authenticated
using (
  bucket_id = 'skc-evidence'
  and (
    public.skc_is_admin()
    or ((storage.foldername(name))[2] = auth.uid()::text)
    or exists (
      select 1
      from public.skc_events e,
           lateral jsonb_array_elements(coalesce(e.payload->'attachments', '[]'::jsonb)) a
      where e.entity_type in ('transactions','messages')
        and coalesce(nullif(a->>'storageKey',''), a->>'remotePath') = storage.objects.name
    )
  )
);

create policy "skc evidence insert" on storage.objects
for insert to authenticated
with check (
  bucket_id = 'skc-evidence'
  and (storage.foldername(name))[2] = auth.uid()::text
);

create policy "skc evidence update" on storage.objects
for update to authenticated
using (
  bucket_id = 'skc-evidence'
  and (storage.foldername(name))[2] = auth.uid()::text
)
with check (
  bucket_id = 'skc-evidence'
  and (storage.foldername(name))[2] = auth.uid()::text
);

create policy "skc evidence delete" on storage.objects
for delete to authenticated
using (
  bucket_id = 'skc-evidence'
  and (
    ((storage.foldername(name))[2] = auth.uid()::text)
    or public.skc_is_admin()
  )
);

-- Politicas del bucket historico de plantillas Excel.
drop policy if exists "skc excel template read" on storage.objects;
drop policy if exists "skc excel template insert" on storage.objects;
drop policy if exists "skc excel template update" on storage.objects;

create policy "skc excel template read" on storage.objects
for select to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin());

create policy "skc excel template insert" on storage.objects
for insert to authenticated
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());

create policy "skc excel template update" on storage.objects
for update to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin())
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());

-- --------------------------------------------------------------------------
-- 6. API SQL administrativa para limpieza desde la app (3.10.0)
-- --------------------------------------------------------------------------
create or replace function public.skc_can_admin_cleanup()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select auth.uid() is not null and public.skc_is_admin()
$$;

revoke all on function public.skc_can_admin_cleanup() from public;
grant execute on function public.skc_can_admin_cleanup() to authenticated;

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
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if not public.skc_is_admin() then
    raise exception 'ADMIN required';
  end if;

  if v_type not in ('transactions','messages','reminders','audit') then
    raise exception 'Entity type is not eligible for cleanup';
  end if;

  if v_id = '' then
    raise exception 'entity id required';
  end if;

  select exists(
    select 1
    from public.skc_events
    where entity_type = v_type
      and entity_id = v_id
      and coalesce(payload->>'_deleted','false') <> 'true'
  ) into v_exists;

  insert into public.skc_events(id, entity_type, entity_id, payload, updated_at, updated_by)
  values (
    v_type || ':' || v_id,
    v_type,
    v_id,
    jsonb_build_object(
      'id', v_id,
      '_deleted', true,
      'deletedAt', v_now,
      'deletedEntityType', v_type
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

revoke all on function public.skc_admin_delete_entity(text,text) from public;
grant execute on function public.skc_admin_delete_entity(text,text) to authenticated;

-- Compatibilidad con 3.7.x - 3.9.x.
create or replace function public.skc_can_delete_invoice()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select public.skc_can_admin_cleanup()
$$;

create or replace function public.skc_delete_invoice(p_transaction_id text)
returns boolean
language sql
security definer
set search_path = public
as $$
  select public.skc_admin_delete_entity('transactions', p_transaction_id)
$$;

revoke all on function public.skc_can_delete_invoice() from public;
grant execute on function public.skc_can_delete_invoice() to authenticated;
revoke all on function public.skc_delete_invoice(text) from public;
grant execute on function public.skc_delete_invoice(text) to authenticated;

-- --------------------------------------------------------------------------
-- 7. Reporte de espacio de la tabla principal para ADMIN
-- --------------------------------------------------------------------------
create or replace function public.skc_admin_space_report()
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_by_type jsonb;
  v_rows bigint;
  v_tombstones bigint;
  v_payload_bytes bigint;
  v_table_bytes bigint;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if not public.skc_is_admin() then
    raise exception 'ADMIN required';
  end if;

  select coalesce(
    jsonb_agg(
      jsonb_build_object(
        'type', q.entity_type,
        'rows', q.rows,
        'tombstones', q.tombstones,
        'payloadBytes', q.payload_bytes
      )
      order by q.entity_type
    ),
    '[]'::jsonb
  )
  into v_by_type
  from (
    select
      entity_type,
      count(*)::bigint as rows,
      count(*) filter (where coalesce(payload->>'_deleted','false') = 'true')::bigint as tombstones,
      coalesce(sum(pg_column_size(payload)),0)::bigint as payload_bytes
    from public.skc_events
    group by entity_type
  ) q;

  select
    count(*)::bigint,
    count(*) filter (where coalesce(payload->>'_deleted','false') = 'true')::bigint,
    coalesce(sum(pg_column_size(payload)),0)::bigint
  into v_rows, v_tombstones, v_payload_bytes
  from public.skc_events;

  select pg_total_relation_size('public.skc_events'::regclass)::bigint
  into v_table_bytes;

  return jsonb_build_object(
    'rows', coalesce(v_rows,0),
    'tombstones', coalesce(v_tombstones,0),
    'payloadBytes', coalesce(v_payload_bytes,0),
    'tableBytes', coalesce(v_table_bytes,0),
    'byType', v_by_type
  );
end;
$$;

revoke all on function public.skc_admin_space_report() from public;
grant execute on function public.skc_admin_space_report() to authenticated;

-- --------------------------------------------------------------------------
-- 8. Purga controlada de tombstones antiguas para ADMIN
-- --------------------------------------------------------------------------
create or replace function public.skc_admin_purge_tombstones(
  p_older_than_days integer default 90
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_days integer := greatest(coalesce(p_older_than_days, 90), 30);
  v_count integer := 0;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if not public.skc_is_admin() then
    raise exception 'ADMIN required';
  end if;

  delete from public.skc_events
  where coalesce(payload->>'_deleted','false') = 'true'
    and updated_at < now() - make_interval(days => v_days);

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

revoke all on function public.skc_admin_purge_tombstones(integer) from public;
grant execute on function public.skc_admin_purge_tombstones(integer) to authenticated;

commit;

-- ============================================================================
-- 9. VERIFICACION (solo lectura)
-- Si todo quedo bien, las columnas de funciones deben mostrar TRUE.
-- ============================================================================
select
  to_regclass('public.skc_events') is not null as skc_events_ok,
  to_regprocedure('public.skc_keep_newest_event()') is not null as tombstone_trigger_function_ok,
  to_regprocedure('public.skc_can_admin_cleanup()') is not null as admin_cleanup_check_ok,
  to_regprocedure('public.skc_admin_delete_entity(text,text)') is not null as admin_delete_entity_ok,
  to_regprocedure('public.skc_delete_invoice(text)') is not null as delete_invoice_compat_ok,
  to_regprocedure('public.skc_admin_space_report()') is not null as space_report_ok,
  to_regprocedure('public.skc_admin_purge_tombstones(integer)') is not null as purge_tombstones_ok;

select id, name, public, file_size_limit
from storage.buckets
where id in ('skc-evidence','skc-excel-templates')
order by id;

select schemaname, tablename, policyname, cmd
from pg_policies
where (schemaname='public' and tablename='skc_events')
   or (schemaname='storage' and tablename='objects' and policyname like 'skc%')
order by schemaname, tablename, policyname;
