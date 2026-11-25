import { Alert, Typography } from 'antd';
import { useContext, useEffect, useMemo, useState } from 'react';
import { ArtifactKinds, type Side } from '../artifacts';
import { useText } from '../language';
import { ReadSpinner } from '../ReadSpinner';
import { PackagesError, type Change, type FileVersion, type PackagesApi } from './api';
import { LineDiff } from '../LineDiff';
import { PackageFileChange } from './PackageFileChange';

export interface FileChangeProps {
  api: PackagesApi;
  repository: string;
  change: Change;
  /** The commit the older version is read at; null when there is none, as before the first commit. */
  before: string | null;
  /** `work` or the commit the newer version is read at. */
  after: string;
  /** Told when the file on disk is no longer the one the change was listed with: the package is to be read again. */
  onStale: () => void;
}

type Read = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'large' } | { kind: 'read'; before: FileVersion | null; after: FileVersion | null };

/** The version of the file at `path` at `rev`, or null when it has none there. */
async function versionOf(api: PackagesApi, repository: string, rev: string, path: string): Promise<FileVersion | null> {
  try {
    return await api.file(repository, rev, path);
  } catch (error) {
    if (error instanceof PackagesError && error.status === 404) return null;
    throw error;
  }
}

/** A side of a comparison: the text of one version, and the files of its package at the same revision. */
function sideOf(api: PackagesApi, repository: string, rev: string, text: string): Side {
  return {
    rev,
    text,
    artifacts: async () => (await api.artifacts(repository, rev)).artifacts,
    read: async (path) => {
      const version = await versionOf(api, repository, rev, path);
      return version !== null && 'text' in version ? version.text : null;
    },
  };
}

/**
 * The readable change of one file of a package between two revisions. A flow, an endpoint or a mapping is drawn by
 * the module that owns its kind; a `package.yaml`, a `package.lock` or a `folder.yaml` shows its fields and its
 * dependencies; a binary file shows its two sizes; any other file shows its line diff. A file over the read limit
 * reads "too large to compare". When the version on disk no longer has the tag the change was listed with, nothing is
 * drawn and `onStale` is told.
 */
export function FileChange({ api, repository, change, before, after, onStale }: FileChangeProps) {
  const kinds = useContext(ArtifactKinds);
  const { t } = useText();
  const [read, setRead] = useState<Read>(change.large ? { kind: 'large' } : { kind: 'reading' });

  useEffect(() => {
    if (change.large) return;
    let current = true;
    const older = change.status === 'added' || before === null ? Promise.resolve(null) : versionOf(api, repository, before, change.renamedFrom ?? change.path);
    const newer = change.status === 'deleted' ? Promise.resolve(null) : versionOf(api, repository, after, change.path);
    Promise.all([older, newer]).then(
      ([b, a]) => {
        if (!current) return;
        if (after === 'work' && (a?.etag ?? null) !== change.tag) onStale();
        else setRead({ kind: 'read', before: b, after: a });
      },
      (error: unknown) => {
        if (!current) return;
        if (error instanceof PackagesError && error.status === 413) setRead({ kind: 'large' });
        else setRead({ kind: 'failed', message: error instanceof Error ? error.message : String(error) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, repository, change, before, after, onStale]);
  // the sides are made once for each read, so that a kind's drawing reads its context once
  const sides = useMemo(() => {
    if (read.kind !== 'read') return null;
    const textOf = (v: FileVersion | null) => (v !== null && 'text' in v ? v.text : null);
    const b = textOf(read.before);
    const a = textOf(read.after);
    return { before: b === null || before === null ? null : sideOf(api, repository, before, b), after: a === null ? null : sideOf(api, repository, after, a) };
  }, [read, api, repository, before, after]);

  if (read.kind === 'large') return <Typography.Text type="secondary">{t('packages.note.large')}</Typography.Text>;
  if (read.kind === 'reading') return <ReadSpinner label={t('packages.file.reading', { path: change.path })} />;
  if (read.kind === 'failed') return <Alert type="error" showIcon title={t('packages.file.unread')} description={read.message} />;
  const { before: b, after: a } = read;
  if ((b !== null && 'binary' in b) || (a !== null && 'binary' in a)) {
    const size = (v: FileVersion | null) => (v === null ? t('packages.file.none') : t('packages.file.bytes', { count: 'binary' in v ? v.size : new TextEncoder().encode(v.text).length }));
    return <Typography.Text>{t('packages.file.binary', { before: size(b), after: size(a) })}</Typography.Text>;
  }
  const kind = kinds.get(change.kind);
  if (kind !== undefined && sides !== null) return <kind.Change path={change.path} before={sides.before} after={sides.after} />;
  if (change.kind === 'package' || change.kind === 'lock' || change.kind === 'folder') {
    return <PackageFileChange path={change.path} before={b?.text ?? null} after={a?.text ?? null} />;
  }
  return <LineDiff before={b?.text ?? ''} after={a?.text ?? ''} />;
}
