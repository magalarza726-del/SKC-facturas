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
