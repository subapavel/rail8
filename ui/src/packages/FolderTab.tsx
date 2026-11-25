import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button } from 'antd';
import { useContext, useEffect, useState } from 'react';
import { ArtifactKinds, type ArtifactKind } from '../artifacts';
import { useText } from '../language';
import { Page } from '../Page';
import { ReadSpinner } from '../ReadSpinner';
import type { Screen } from '../screens';
import type { Artifact } from './api';
import { ArtifactCards } from './PackageTab';

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; artifacts: Artifact[] };

/** The artifacts every kind of `kinds` that lists its files lists in the working folder, the kinds one after the other. */
export async function listedArtifacts(kinds: ReadonlyMap<string, ArtifactKind>, signal?: AbortSignal): Promise<Artifact[]> {
  return (await Promise.all([...kinds.values()].map((kind) => kind.list?.(signal) ?? []))).flat();
}

/**
 * The tab of a folder of the working folder that is no package but holds artifacts, such as the corpus of the mapping
 * studio or the package folder of an adapter: a page with the folder's name as its title, a line saying that it is no
 * package, Refresh, and the cards of the kinds of artifact it holds, each artifact opening in its editor's tab, each
 * card with its kind's New action, such as New mapping, on a server that takes writes when `writable`.
 * It has no changes, history, commit or deploy: git does not keep the folder.
 */
export function FolderTab({ folder, writable, open }: { folder: string; writable: boolean; open: (screen: Screen) => void }) {
  const kinds = useContext(ArtifactKinds);
  const { t } = useText();
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = () => setAsked((n) => n + 1);

  useEffect(() => {
    const controller = new AbortController();
    listedArtifacts(kinds, controller.signal).then(
      (artifacts) => !controller.signal.aborted && setResult({ kind: 'read', artifacts: artifacts.filter(({ path }) => path.startsWith(`${folder}/`)) }),
      (error: unknown) => !controller.signal.aborted && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => controller.abort();
  }, [kinds, folder, asked]);

  let body;
  if (result.kind === 'reading') body = <ReadSpinner label={t('packages.folder.reading', { folder })} />;
  else if (result.kind === 'failed')
    body = <Alert type="error" showIcon title={t('packages.folder.unread', { folder })} description={result.message} action={<Button onClick={refresh}>{t('frame.tryAgain')}</Button>} />;
  else {
    const held = [...new Set(result.artifacts.map(({ kind }) => kind))];
    body = <ArtifactCards artifacts={result.artifacts} folder={folder} writable={writable} open={open} onCreated={refresh} creates={held} />;
  }

  return (
    <Page
      title={folder}
      content={t('packages.folder.content')}
      extra={
        <Button icon={<ReloadOutlined aria-hidden />} onClick={refresh}>
          {t('packages.refresh')}
        </Button>
      }
    >
      {body}
    </Page>
  );
}
