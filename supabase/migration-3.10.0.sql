-- SKC Facturas 3.10.0
-- Administración de espacio desde la app.
-- Permite a usuarios ADMIN reducir entidades antiguas a tombstones mínimas,
-- consultar el uso real de public.skc_events y purgar tombstones antiguas.
-- No expone service_role ni permite borrar users/catalogs/appConfig/ledger.

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

-- Compatibilidad con versiones 3.7-3.9.
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

  select pg_total_relation_size('public.skc_events'::regclass)::bigint into v_table_bytes;

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

create or replace function public.skc_admin_purge_tombstones(p_older_than_days integer default 90)
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

-- Un ADMIN necesita poder inventariar el bucket completo para detectar archivos huérfanos.
drop policy if exists "skc evidence read" on storage.objects;
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

-- El borrado físico de evidencias continúa restringido a propietario o ADMIN.
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
