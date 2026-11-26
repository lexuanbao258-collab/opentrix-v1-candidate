// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0

import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import type {
  ApiError,
  EntityInfo,
  FieldSchema,
  PlatformClient,
  PlatformRecord,
  RecordPage,
} from "../../services/platform/platformClient";

const PAGE = 10;

function label(name: Record<string, string> | undefined, fallback: string) {
  return name?.vi ?? name?.en ?? fallback;
}

function describe(status: number, error?: ApiError) {
  if (status === 409) return "Bản ghi đã bị người khác thay đổi. Danh sách đã được tải lại, hãy thử lại.";
  if (status === 403) return "Bạn không có quyền thực hiện thao tác này.";
  if (status === 404) return "Không tìm thấy ứng dụng, danh mục hoặc bản ghi.";
  if (status === 400) return error?.message ?? "Dữ liệu không hợp lệ.";
  if (status === 503) return "Không xác định được kết quả ghi. Hãy tải lại danh sách trước khi thử lại.";
  return "Yêu cầu thất bại. Vui lòng thử lại.";
}

/** Converts form strings to the JSON types declared by the entity schema; empty optional fields are omitted. */
export function toData(entity: EntityInfo, form: FormData) {
  const data: Record<string, unknown> = {};
  for (const [field, schema] of Object.entries(entity.schema.properties)) {
    if (schema.type === "boolean") {
      data[field] = form.get(field) === "on";
      continue;
    }
    const raw = String(form.get(field) ?? "");
    if (raw === "" && !entity.schema.required?.includes(field)) continue;
    data[field] = schema.type === "integer" || schema.type === "number" ? Number(raw) : raw;
  }
  return data;
}

function Field({ name, schema, required, value }: { name: string; schema: FieldSchema; required: boolean; value: unknown }) {
  const id = `platform-field-${name}`;
  const title = schema.title ?? name;
  if (schema.type === "boolean") {
    return (
      <label className="platform-check" htmlFor={id}>
        <input id={id} name={name} type="checkbox" defaultChecked={value === true} /> {title}
      </label>
    );
  }
  return (
    <label htmlFor={id}>
      {title}
      {required ? " *" : ""}
      {schema.enum ? (
        <select id={id} name={name} defaultValue={String(value ?? "")} required={required}>
          {!required && <option value="">—</option>}
          {schema.enum.map((option) => (
            <option key={String(option)} value={String(option)}>
              {String(option)}
            </option>
          ))}
        </select>
      ) : (
        <input
          id={id}
          name={name}
          type={schema.type === "string" ? (schema.format === "date" ? "date" : "text") : "number"}
          defaultValue={value === undefined ? "" : String(value)}
          maxLength={schema.maxLength}
          required={required}
        />
      )}
    </label>
  );
}

export function PlatformRecords({ client }: { client: PlatformClient }) {
  const { app = "it-helpdesk", entity: entityKey = "category" } = useParams();
  const [entity, setEntity] = useState<EntityInfo | null>(null);
  const [page, setPage] = useState<RecordPage | null>(null);
  const [query, setQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [editing, setEditing] = useState<PlatformRecord | "new" | null>(null);
  const [message, setMessage] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const fields = useMemo(() => (entity ? Object.entries(entity.schema.properties) : []), [entity]);

  const load = useCallback(
    async (q: string, from: number) => {
      const result = await client.search(app, entityKey, { q: q || undefined, limit: PAGE, offset: from });
      if (result.ok) setPage(result.body);
      else setMessage(describe(result.status, result.error));
    },
    [client, app, entityKey],
  );

  useEffect(() => {
    let active = true;
    client.entity(app, entityKey).then((result) => {
      if (!active) return;
      if (result.ok) {
        setEntity(result.body);
        void load("", 0);
      } else setMessage(describe(result.status, result.error));
    });
    return () => {
      active = false;
    };
  }, [client, app, entityKey, load]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!entity || !editing || busy) return;
    setBusy(true);
    setFieldErrors({});
    const data = toData(entity, new FormData(event.currentTarget));
    const result =
      editing === "new"
        ? await client.create(app, entityKey, data)
        : await client.update(app, entityKey, editing.id, editing.version, data);
    setBusy(false);
    if (result.ok) {
      setEditing(null);
      setMessage(editing === "new" ? "Đã tạo bản ghi." : "Đã lưu thay đổi.");
      await load(query, offset);
    } else {
      setMessage(describe(result.status, result.error));
      setFieldErrors(result.error?.errors ?? {});
      if (result.status === 409) {
        setEditing(null);
        await load(query, offset);
      }
    }
  }

  async function remove(record: PlatformRecord) {
    if (busy || !window.confirm("Xóa bản ghi này?")) return;
    setBusy(true);
    const result = await client.remove(app, entityKey, record.id, record.version);
    setBusy(false);
    setMessage(result.ok ? "Đã xóa bản ghi." : describe(result.status, result.error));
    await load(query, offset);
  }

  if (!entity) {
    return <p role={message ? "alert" : "status"}>{message || "Đang tải danh mục…"}</p>;
  }
  const can = entity.allowedOperations;
  return (
    <section className="panel platform-records" aria-labelledby="platform-title">
      <header className="platform-records-header">
        <div>
          <p className="helpdesk-kicker">Platform API · {app}</p>
          <h1 id="platform-title">{label(entity.name, entity.key)}</h1>
        </div>
        {can.create && (
          <button className="button primary" onClick={() => { setEditing("new"); setFieldErrors({}); }}>
            Thêm mới
          </button>
        )}
      </header>

      {message && <p className="inline-notice" role="status">{message}</p>}

      <form
        className="platform-search"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          setOffset(0);
          void load(query, 0);
        }}
      >
        <label htmlFor="platform-q">Tìm theo {entity.searchFields.join(", ")}</label>
        <input id="platform-q" value={query} maxLength={100} onChange={(event) => setQuery(event.target.value)} />
        <button className="button secondary" type="submit">Tìm</button>
      </form>

      {editing && (
        <form
          key={editing === "new" ? "new" : `${editing.id}:${editing.version}`}
          className="platform-form"
          onSubmit={submit} aria-label={editing === "new" ? "Thêm bản ghi" : "Sửa bản ghi"}>
          {fields.map(([name, schema]) => (
            <div key={name}>
              <Field
                name={name}
                schema={schema}
                required={entity.schema.required?.includes(name) ?? false}
                value={editing === "new" ? undefined : editing.data[name]}
              />
              {fieldErrors[`/${name}`] && <small role="alert">{fieldErrors[`/${name}`]}</small>}
            </div>
          ))}
          <div className="platform-form-actions">
            <button className="button primary" type="submit" disabled={busy}>Lưu</button>
            <button className="button secondary" type="button" onClick={() => setEditing(null)}>Hủy</button>
          </div>
        </form>
      )}

      <div className="platform-table-wrap">
        <table>
          <caption>
            {page ? `${page.total} bản ghi` : "Đang tải…"}
          </caption>
          <thead>
            <tr>
              {fields.map(([name, schema]) => <th key={name} scope="col">{schema.title ?? name}</th>)}
              <th scope="col">Phiên bản</th>
              <th scope="col">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {page?.items.map((record) => (
              <tr key={record.id}>
                {fields.map(([name]) => <td key={name}>{String(record.data[name] ?? "")}</td>)}
                <td>{record.version}</td>
                <td className="platform-row-actions">
                  {can.update && (
                    <button className="button secondary" onClick={() => { setEditing(record); setFieldErrors({}); }}>
                      Sửa
                    </button>
                  )}
                  {can.delete && (
                    <button className="button secondary" onClick={() => void remove(record)}>Xóa</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {page && page.total > PAGE && (
        <nav className="platform-pager" aria-label="Phân trang">
          <button className="button secondary" disabled={offset === 0}
            onClick={() => { const next = Math.max(0, offset - PAGE); setOffset(next); void load(query, next); }}>
            Trước
          </button>
          <span>{offset + 1}–{Math.min(offset + PAGE, page.total)} / {page.total}</span>
          <button className="button secondary" disabled={offset + PAGE >= page.total}
            onClick={() => { const next = offset + PAGE; setOffset(next); void load(query, next); }}>
            Sau
          </button>
        </nav>
      )}
    </section>
  );
}
