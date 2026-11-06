
create extension if not exists pgcrypto;

create table if not exists public.skc_events (
  id text primary key,
  entity_type text not null check (entity_type in ('users','catalogs','appConfig','transactions','reminders','messages','ledger','audit')),
  entity_id text not null,
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  updated_by uuid null default auth.uid(),
  created_at timestamptz not null default now()
);

create index if not exists skc_events_updated_at_idx on public.skc_events(updated_at);
create index if not exists skc_events_type_idx on public.skc_events(entity_type, updated_at);
create unique index if not exists skc_events_type_entity_idx on public.skc_events(entity_type, entity_id);

alter table public.skc_events drop constraint if exists skc_events_entity_type_check;
alter table public.skc_events add constraint skc_events_entity_type_check
  check (entity_type in ('users','catalogs','appConfig','transactions','reminders','messages','ledger','audit')) not valid;
alter table public.skc_events validate constraint skc_events_entity_type_check;

alter table public.skc_events drop constraint if exists skc_events_identity_consistency;
alter table public.skc_events add constraint skc_events_identity_consistency
  check (id = entity_type || ':' || entity_id and payload->>'id' = entity_id) not valid;
alter table public.skc_events validate constraint skc_events_identity_consistency;

create or replace function public.skc_keep_newest_event()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  if old.updated_at > new.updated_at then
    return old;
  end if;
  new.updated_by := auth.uid();
  return new;
end;
$$;

drop trigger if exists skc_keep_newest_event_trigger on public.skc_events;
create trigger skc_keep_newest_event_trigger
before update on public.skc_events
for each row execute function public.skc_keep_newest_event();

alter table public.skc_events enable row level security;
revoke all on public.skc_events from anon;
grant select, insert, update on public.skc_events to authenticated;
revoke delete on public.skc_events from authenticated;

create or replace function public.skc_current_profile_id()
returns text
language sql
stable
security definer
set search_path = public
as $$
  select e.entity_id
  from public.skc_events e
  where e.entity_type='users'
    and coalesce(e.payload->>'active','true') <> 'false'
    and (
      nullif(e.payload->>'authUserId','') = auth.uid()::text
      or (
        coalesce(e.payload->>'authUserId','') = ''
        and lower(coalesce(e.payload->>'email','')) = lower(coalesce(auth.jwt()->>'email',''))
      )
    )
  order by
    case when nullif(e.payload->>'authUserId','') = auth.uid()::text then 0 else 1 end,
    e.updated_at desc
  limit 1
$$;

create or replace function public.skc_is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists(
    select 1
    from public.skc_events e
    where e.entity_type='users'
      and e.entity_id = public.skc_current_profile_id()
      and e.payload->>'role'='ADMIN'
      and coalesce(e.payload->>'active','true') <> 'false'
  )
$$;

create or replace function public.skc_bind_my_profile()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_profile_id text;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select e.entity_id
    into v_profile_id
  from public.skc_events e
  where e.entity_type='users'
    and coalesce(e.payload->>'active','true') <> 'false'
    and (
      nullif(e.payload->>'authUserId','') = auth.uid()::text
      or (
        coalesce(e.payload->>'authUserId','') = ''
        and lower(coalesce(e.payload->>'email','')) = lower(coalesce(auth.jwt()->>'email',''))
      )
    )
  order by
    case when nullif(e.payload->>'authUserId','') = auth.uid()::text then 0 else 1 end,
    e.updated_at desc
  limit 1;

  if v_profile_id is null then
    return null;
  end if;

  update public.skc_events
  set payload = jsonb_set(payload, '{authUserId}', to_jsonb(auth.uid()::text), true),
      updated_at = now(),
      updated_by = auth.uid()
  where entity_type='users'
    and entity_id=v_profile_id
    and (
      coalesce(payload->>'authUserId','') = ''
      or payload->>'authUserId' = auth.uid()::text
    );

  return v_profile_id;
end;
$$;

create or replace function public.skc_has_configured_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists(
    select 1 from public.skc_events e
    where e.entity_type='users'
      and e.payload->>'role'='ADMIN'
      and length(trim(coalesce(e.payload->>'email',''))) > 0
      and coalesce(e.payload->>'active','true') <> 'false'
  )
$$;

grant execute on function public.skc_current_profile_id() to authenticated;
grant execute on function public.skc_is_admin() to authenticated;
grant execute on function public.skc_has_configured_admin() to authenticated;
grant execute on function public.skc_bind_my_profile() to authenticated;

drop policy if exists "skc authenticated read" on public.skc_events;
drop policy if exists "skc authenticated insert" on public.skc_events;
drop policy if exists "skc authenticated update" on public.skc_events;
drop policy if exists "skc authenticated delete" on public.skc_events;
drop policy if exists "skc role read" on public.skc_events;
drop policy if exists "skc role insert" on public.skc_events;
drop policy if exists "skc role update" on public.skc_events;

create policy "skc role read" on public.skc_events
for select to authenticated using (
  entity_type in ('users','catalogs','appConfig','ledger')
  or public.skc_is_admin()
  or (entity_type='transactions' and (payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
);

create policy "skc role insert" on public.skc_events
for insert to authenticated with check (
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type='transactions' and payload->>'createdByUserId'=public.skc_current_profile_id())
  or (entity_type='messages' and payload->>'senderUserId'=public.skc_current_profile_id())
  or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
  or (entity_type='ledger' and payload->>'createdByUserId'=public.skc_current_profile_id())
  or (entity_type='audit' and payload->>'actorUserId'=public.skc_current_profile_id())
);

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
  (entity_type='users' and (public.skc_is_admin() or not public.skc_has_configured_admin()))
  or (entity_type in ('catalogs','appConfig') and public.skc_is_admin())
  or (entity_type='transactions' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (public.skc_is_admin() or payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and (public.skc_is_admin() or payload->>'userId'=public.skc_current_profile_id()))
  or (entity_type='ledger' and (public.skc_is_admin() or payload->>'createdByUserId'=public.skc_current_profile_id()))
  or (entity_type='audit' and public.skc_is_admin())
);

update public.skc_events
set payload = jsonb_set(
      payload,
      '{email}',
      to_jsonb((case entity_id
        when 'administracion' then 'administracion@gmail.skc'
        when 'dalton' then 'dalton@gmail.skc'
        when 'evelyn' then 'evelyn@gmail.skc'
        when 'javier' then 'javier@gmail.skc'
        when 'karen' then 'karen@gmail.skc'
        when 'tito' then 'tito@gmail.skc'
      end)::text),
      true
    ) || case when entity_id='dalton' then '{"role":"ADMIN"}'::jsonb else '{}'::jsonb end
      || case when entity_id='administracion' then '{"role":"ADMIN","transferEnabled":true}'::jsonb else '{}'::jsonb end,
    updated_at = now()
where entity_type='users'
  and entity_id in ('administracion','dalton','evelyn','javier','karen','tito');

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'skc-evidence',
  'skc-evidence',
  false,
  20971520,
  array['application/pdf','image/jpeg','image/png','image/webp','image/gif','image/heic','image/heif']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "skc evidence read" on storage.objects;
create policy "skc evidence read" on storage.objects for select to authenticated using (bucket_id='skc-evidence');
drop policy if exists "skc evidence insert" on storage.objects;
create policy "skc evidence insert" on storage.objects for insert to authenticated with check (bucket_id='skc-evidence');
drop policy if exists "skc evidence update" on storage.objects;
create policy "skc evidence update" on storage.objects for update to authenticated using (bucket_id='skc-evidence') with check (bucket_id='skc-evidence');
drop policy if exists "skc evidence delete" on storage.objects;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'skc-excel-templates',
  'skc-excel-templates',
  false,
  10485760,
  array['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/zip','application/octet-stream']
)
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "skc excel template read" on storage.objects;
create policy "skc excel template read" on storage.objects
for select to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin());

drop policy if exists "skc excel template insert" on storage.objects;
create policy "skc excel template insert" on storage.objects
for insert to authenticated
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());

drop policy if exists "skc excel template update" on storage.objects;
create policy "skc excel template update" on storage.objects
for update to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin())
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());
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

drop policy if exists "skc evidence read" on storage.objects;
drop policy if exists "skc evidence insert" on storage.objects;
drop policy if exists "skc evidence update" on storage.objects;
drop policy if exists "skc evidence delete" on storage.objects;

create policy "skc evidence read" on storage.objects
for select to authenticated
using (
  bucket_id = 'skc-evidence'
  and (
    ((storage.foldername(name))[2] = auth.uid()::text)
    or (
      public.skc_is_admin()
      and name like '%/excel-templates/%'
    )
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
    or (public.skc_is_admin() and name like '%/excel-templates/%')
  )
);

-- El bucket anterior de formatos se conserva para que plantillas de versiones previas sigan descargándose.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'skc-excel-templates',
  'skc-excel-templates',
  false,
  10485760,
  array['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/zip','application/octet-stream']
)
on conflict (id) do nothing;

drop policy if exists "skc excel template read" on storage.objects;
create policy "skc excel template read" on storage.objects
for select to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin());

drop policy if exists "skc excel template insert" on storage.objects;
create policy "skc excel template insert" on storage.objects
for insert to authenticated
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());

drop policy if exists "skc excel template update" on storage.objects;
create policy "skc excel template update" on storage.objects
for update to authenticated
using (bucket_id='skc-excel-templates' and public.skc_is_admin())
with check (bucket_id='skc-excel-templates' and public.skc_is_admin());

-- ---- SKC Facturas 3.7.0: limpieza segura de facturas antiguas ----
create or replace function public.skc_keep_newest_event()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  if coalesce(new.payload->>'_deleted','false') = 'true' then
    new.updated_by := auth.uid();
    return new;
  end if;
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
    select 1 from public.skc_events
    where entity_type='transactions'
      and entity_id=p_transaction_id
      and coalesce(payload->>'_deleted','false') <> 'true'
  ) into v_exists;

  insert into public.skc_events(id, entity_type, entity_id, payload, updated_at, updated_by)
  values (
    'transactions:' || p_transaction_id,
    'transactions',
    p_transaction_id,
    jsonb_build_object('id', p_transaction_id, '_deleted', true, 'deletedAt', v_now),
    v_now,
    auth.uid()
  )
  on conflict (id) do update
  set payload=excluded.payload,
      updated_at=excluded.updated_at,
      updated_by=excluded.updated_by;

  return v_exists;
end;
$$;

revoke all on function public.skc_delete_invoice(text) from public;
grant execute on function public.skc_delete_invoice(text) to authenticated;

drop policy if exists "skc evidence delete" on storage.objects;
create policy "skc evidence delete" on storage.objects
for delete to authenticated
using (
  bucket_id='skc-evidence'
  and (
    ((storage.foldername(name))[2] = auth.uid()::text)
    or public.skc_is_admin()
  )
);

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


-- ===== SKC Facturas 3.10.0: administración de espacio =====
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

-- -----------------------------------------------------------------------------
-- 3.15.2: borrado físico de mensajes y limpieza administrativa sin tombstones.
-- -----------------------------------------------------------------------------
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
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if v_id = '' then raise exception 'message id required'; end if;

  select payload into v_payload
    from public.skc_events
   where entity_type = 'messages' and entity_id = v_id
   limit 1;
  if v_payload is null then return false; end if;

  v_is_admin := public.skc_is_admin();
  v_profile_id := public.skc_current_profile_id();
  if not v_is_admin and coalesce(v_payload->>'senderUserId','') <> coalesce(v_profile_id,'') then
    raise exception 'Only sender or ADMIN can delete this message';
  end if;
  if not v_is_admin and upper(coalesce(v_payload->>'status','')) in ('CONFIRMADO','COMPLETADA') then
    raise exception 'Confirmed movement requires ADMIN correction';
  end if;

  delete from public.skc_events where entity_type='messages' and entity_id=v_id;
  return found;
end;
$$;
revoke all on function public.skc_delete_message_physical(text) from public;
grant execute on function public.skc_delete_message_physical(text) to authenticated;

create or replace function public.skc_admin_delete_entity(p_entity_type text, p_entity_id text)
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
  select exists(select 1 from public.skc_events where entity_type=v_type and entity_id=v_id) into v_exists;
  delete from public.skc_events where entity_type=v_type and entity_id=v_id;
  return v_exists;
end;
$$;
revoke all on function public.skc_admin_delete_entity(text,text) from public;
grant execute on function public.skc_admin_delete_entity(text,text) to authenticated;
