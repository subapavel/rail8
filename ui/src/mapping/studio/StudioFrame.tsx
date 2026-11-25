import './studio.css';
import { Alert, Button, Empty, Flex, Tabs } from 'antd';
import {
  CheckCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  EditOutlined,
  ExperimentOutlined,
  PartitionOutlined,
  ProfileOutlined,
  SaveOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useStore } from 'zustand';
import { describeError, type ApiClient } from '../api/client';
import { describeRefusal, type Refusal } from '../model/command';
import { msg, say, useText, type Label, type Message } from '../../language';
import { AboutStudio } from './AboutStudio';
import type { BrowserStateStore } from './browser-state/browser-state';
import { DefinitionTab } from './definition/DefinitionTab';
import { DiscardDialog } from '../../DiscardDialog';
import type { UnsavedChanges } from '../../screens';
import { useWorkingFile } from '../../workingFiles';
import { FunctionsTab } from './functions/FunctionsTab';
import { MappingOverview } from './overview/MappingOverview';
import { SaveConflict } from './SaveConflict';
import { CopyTextButton } from './shared/CopyTextButton';
import { Signature } from './signature/Signature';
import { TestTab } from './test/TestTab';
import { refreshMutation } from './shared/structure-query';
import { useTestSession } from './shared/test-session';
import { selectUnsaved, type StudioTab, type WorkingCopyState, type WorkingCopyStore } from './store/working-copy';

/** The tabs the studio shows, in their order, each with the id of its title and its icon. */
const VIEWS = [
  ['overview', 'studio.tab.overview', ProfileOutlined],
  ['definition', 'studio.tab.definition', PartitionOutlined],
  ['test', 'studio.tab.test', ExperimentOutlined],
  ['signature', 'studio.tab.signature', SwapOutlined],
  ['functions', 'studio.tab.functions', CodeOutlined],
] as const satisfies readonly (readonly [StudioTab, string, typeof ProfileOutlined])[];

/** The key of the tab a mapping was last left on, kept per mapping as a viewer preference. */
export const lastTabKey = (mappingId: string) => `mapping:${mappingId}:studio-tab`;

/** The tab a mapping opens on: the one it was last left on when the studio shows it, else Overview. */
export function openingTab(kept: unknown): StudioTab {
  return VIEWS.find(([tab]) => tab === kept)?.[0] ?? 'overview';
}

/**
 * What a check finds in the working copy, over the real compile: a missing source or target in our words, then the
 * failure of the latest compile, or else each of its errors and warnings but the missing structures already named.
 */
function checkIssues({ workingCopy, compileFailure, diagnostics }: WorkingCopyState): Message[] {
  const issues: Message[] = [];
  if (workingCopy.source === undefined) issues.push(msg('studio.check.noSource'));
  if (workingCopy.target === undefined) issues.push(msg('studio.check.noTarget'));
  if (compileFailure !== null) return [...issues, msg('studio.check.notCompiled', { reason: compileFailure })];
  for (const { code, severity, message } of diagnostics) {
    if (code !== 'missing-structure') issues.push(msg(severity === 'error' ? 'studio.check.error' : 'studio.check.warning', { message }));
  }
  return issues;
}

/** The keys that move between the tabs, to the index they move to from `index` of `count`. */
function movedTo(key: string, index: number, count: number): number | undefined {
  switch (key) {
    case 'Home':
      return 0;
    case 'End':
      return count - 1;
    case 'ArrowLeft':
      return (index + count - 1) % count;
    case 'ArrowRight':
      return (index + 1) % count;
    default:
      return undefined;
  }
}

/**
 * Arrow keys, Home and End move the focus to another tab of the tab list named `views` and choose it: only the active
 * tab is in the tab order.
 */
function navigateTabs(event: KeyboardEvent<HTMLElement>, views: string) {
  const target = event.target as HTMLElement;
  if (target.getAttribute('role') !== 'tab') return;
  const list = target.closest('[role="tablist"]');
  if (!list || list.getAttribute('aria-label') !== views) return;
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.stopPropagation();
    return;
  }
  const tabs = [...list.querySelectorAll<HTMLElement>('[role="tab"]')];
  const next = movedTo(event.key, tabs.indexOf(target), tabs.length);
  if (next === undefined) return;
  event.preventDefault();
  event.stopPropagation();
  tabs[next]!.focus();
  tabs[next]!.click();
}

export interface StudioFrameProps {
  /** The mapping's id, its workspace path; its last segment is the mapping's name. */
  mappingId: string;
  api: ApiClient;
  /** The open mapping's store, whose UI slice holds the tab and edit mode. */
  store: WorkingCopyStore;
  /** The port for viewer preferences: the tab chosen is kept there per mapping, and the previews and panel sizes of the Signature and Definition tabs, and the functions. */
  browserState: BrowserStateStore;
  /** Told after each save the service accepted. */
  onSaved?: () => void;
  /** Given what saves the mapping while it has unsaved changes, so that its tab can save them before it closes, and null when it has none. */
  guardClose?: (changes: UnsavedChanges | null) => void;
}

/**
 * The Mapping studio around one open mapping: a line with the studio's tabs, the mapping's name, and Edit, or Cancel,
 * Check and Save in edit mode, then the active tab's body. Arrow keys, Home and End move between the tabs; the tab
 * chosen is kept in the browser state for the mapping's next open. The structures are refreshed once on open; Save sends
 * `If-Match` and meets a save conflict in its dialog; Cancel with unsaved changes asks before it discards them; while
 * there are some, `guardClose` is given the studio's save; the page asks before it unloads while the working copy differs from the baseline. While
 * the studio is shown, Check and F7 check the working copy, and Ctrl+S and Cmd+S save it. A command the store
 * refuses shows why in an inline warning the author can dismiss.
 */
export function StudioFrame({ mappingId, api, store, browserState, onSaved, guardClose }: StudioFrameProps) {
  const name = mappingId.slice(mappingId.lastIndexOf('/') + 1);
  const tab = useStore(store, (s) => s.tab);
  const hasMessages = useStore(store, (s) => s.workingCopy.source !== undefined && s.workingCopy.target !== undefined);
  const editing = useStore(store, (s) => s.editing);
  const unsaved = useStore(store, selectUnsaved);
  const panelRef = useRef<HTMLElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const testSession = useTestSession(store, api, mappingId, browserState);
  const texts = useText();
  const { t } = texts;

  const changeTab = useCallback(
    (next: StudioTab) => {
      store.getState().setTab(next);
      browserState.write(lastTabKey(mappingId), next);
    },
    [store, browserState, mappingId],
  );

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  const [discarding, setDiscarding] = useState(false);

  // Refresh on open. The answer is taken in mutate's callbacks, which TanStack Query calls only while the
  // studio is open: an answer that comes after it closed leaves the store alone.
  const [notice, setNotice] = useState<Label | null>(null);
  const { mutate: refresh } = useMutation(refreshMutation(api, mappingId, store));
  useEffect(
    () =>
      refresh(undefined, {
        onSuccess: ({ sent, answer }) => {
          const orphaned = store.getState().applyRefresh(sent, answer);
          if (orphaned !== null) setNotice(msg('studio.notice.orphaned', { orphaned }));
        },
        onError: (error) => setNotice(msg('studio.notice.notRefreshed', { reason: describeError(error).title })),
      }),
    [refresh, store],
  );

  // A compile the store scheduled after an edit must not answer into a closed studio.
  useEffect(() => () => store.getState().cancelCompile(), [store]);

  // Check: a compile of the working copy as it is now; its answer is the result, an edit before it ends the check.
  const [check, setCheck] = useState<Message[] | null>(null);
  const [checking, setChecking] = useState(false);
  useEffect(
    () =>
      store.subscribe((state, previous) => {
        if (state.workingCopy === previous.workingCopy) return;
        setCheck(null);
        setChecking(false);
      }),
    [store],
  );
  useEffect(() => {
    if (!checking) return undefined;
    const answered = store.getState().compiles;
    const unsubscribe = store.subscribe((state) => {
      if (state.compiles === answered) return;
      setCheck(checkIssues(state));
      setChecking(false);
    });
    store.getState().scheduleCompile();
    return unsubscribe;
  }, [checking, store]);
  const runCheck = () => {
    if (!editing || !unsaved) return;
    setNotice(null);
    setCheck(null);
    setChecking(true);
  };

  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<ReturnType<typeof describeError> | null>(null);
  const [saved, setSaved] = useState(0);
  /** Saves the working copy: true once it is saved; false when it is not, and the studio says why. */
  const save = async (): Promise<boolean> => {
    if (!editing || !unsaved || saving) return false;
    setSaving(true);
    setFailure(null);
    try {
      await store.getState().save();
      // a save conflict resolves too: its dialog asks, and nothing was saved
      if (store.getState().conflict) return false;
      setSaved((n) => n + 1);
      onSaved?.();
      return true;
    } catch (error) {
      setFailure(describeError(error));
      return false;
    } finally {
      setSaving(false);
    }
  };

  // the save handed over is the one of the latest render, which knows the working copy as it is now
  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.({ save });
    return () => guardClose?.(null);
  });

  useWorkingFile(`${mappingId}.mapping.json`, () => unsaved, () =>
    void store.getState().reload().catch((error: unknown) => setNotice(msg('studio.notice.notReread', { reason: describeError(error).title }))),
  );

  // Ctrl+S or Cmd+S saves and F7 checks, as Save and Check do, in the studio shown; a key pressed in a dialog is the
  // dialog's. The studios of the other open mappings sit in hidden tabs.
  useEffect(() => {
    const press = (event: globalThis.KeyboardEvent) => {
      if (!panelRef.current?.checkVisibility()) return;
      if ([...document.querySelectorAll<HTMLElement>('[role="dialog"]')].some((dialog) => dialog.checkVisibility())) return;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void save();
      } else if (event.key === 'F7') {
        event.preventDefault();
        runCheck();
      }
    };
    window.addEventListener('keydown', press);
    return () => window.removeEventListener('keydown', press);
  });

  const leaveEditing = () => {
    setDiscarding(false);
    setFailure(null);
    store.getState().discard();
    requestAnimationFrame(() => editButtonRef.current?.focus());
  };

  // A refused command says why until it is dismissed or a command applies; each refusal is a new object, so the next one shows.
  const refusal = useStore(store, (s) => s.refusal);
  const [dismissed, setDismissed] = useState<Refusal | null>(null);

  useEffect(() => {
    if (!saved) return;
    const timeout = window.setTimeout(() => setSaved(0), 8000);
    return () => window.clearTimeout(timeout);
  }, [saved]);
  useEffect(() => {
    // Name the tab list and keep each tab's name stable while its keyboard hint is announced.
    const list = panelRef.current?.querySelector('[role="tablist"]');
    list?.setAttribute('aria-label', t('studio.views'));
    list?.querySelectorAll('[role="tab"]').forEach((item, index) => item.setAttribute('aria-label', t(VIEWS[index]![1])));
  }, [t]);

  const panel = {
    overview: <MappingOverview mappingId={mappingId} store={store} />,
    definition: hasMessages ? (
      <DefinitionTab
        mappingId={mappingId}
        api={api}
        store={store}
        session={testSession}
        browserState={browserState}
        onChooseMessages={() => changeTab('signature')}
      />
    ) : (
      <ChooseMessages onChoose={() => changeTab('signature')} />
    ),
    test: hasMessages ? (
      <TestTab mappingId={mappingId} store={store} session={testSession} browserState={browserState} />
    ) : (
      <ChooseMessages onChoose={() => changeTab('signature')} />
    ),
    signature: <Signature mappingId={mappingId} api={api} store={store} browserState={browserState} />,
    functions: <FunctionsTab mappingId={mappingId} api={api} store={store} browserState={browserState} />,
  };

  return (
    <section className="rail8-page studio-workspace" ref={panelRef}>
      <Flex align="center" justify="space-between" wrap gap="small">
        <h1>{name}</h1>
        <Flex align="center" gap="small">
          {editing ? (
            <>
              <Button onClick={() => (unsaved ? setDiscarding(true) : leaveEditing())}>{t('frame.cancel')}</Button>
              <Button icon={<CheckCircleOutlined aria-hidden />} disabled={!unsaved || checking} onClick={runCheck}>
                {checking ? t('studio.checking') : t('studio.check')}
              </Button>
              <Button type="primary" icon={<SaveOutlined aria-hidden />} disabled={!unsaved || saving} onClick={() => void save()}>
                {t('studio.save')}
              </Button>
            </>
          ) : (
            <Button ref={editButtonRef} type="primary" icon={<EditOutlined aria-hidden />} onClick={() => store.getState().startEditing()}>
              {t('studio.edit')}
            </Button>
          )}
          <AboutStudio />
        </Flex>
      </Flex>
      {check !== null && <CheckResult issues={check} onDismiss={() => setCheck(null)} />}
      {notice !== null && (
        <Alert type="info" role="status" showIcon title={<h3>{say(texts, notice)}</h3>} action={<Dismiss label={t('studio.dismiss.message')} onClick={() => setNotice(null)} />} />
      )}
      {failure && (
        <Alert
          type="error"
          showIcon
          title={<h3>{failure.title}</h3>}
          description={
            <>
              <p>{failure.detail}</p>
              <ul aria-label={t('studio.save.diagnostics')}>
                {failure.diagnostics.map(({ severity, code, message }, i) => (
                  <li key={i}>
                    {t(`studio.severity.${severity}`)} <code>{code}</code>: {message}
                  </li>
                ))}
              </ul>
            </>
          }
        />
      )}
      {refusal !== null && refusal !== dismissed && (
        <Alert
          type="warning"
          showIcon
          title={<h3>{describeRefusal(refusal, texts)}</h3>}
          action={<Dismiss label={t('studio.dismiss.refusal')} onClick={() => setDismissed(refusal)} />}
        />
      )}
      <div onKeyDownCapture={(event) => navigateTabs(event, t('studio.views'))}>
        <Tabs
          activeKey={tab}
          onChange={(key) => changeTab(key as StudioTab)}
          animated={false}
          destroyOnHidden
          items={VIEWS.map(([key, label, Icon]) => ({ key, label: t(label), icon: <Icon aria-hidden />, children: panel[key] }))}
        />
      </div>
      <DiscardDialog isOpen={discarding} name={name} onDiscard={leaveEditing} onKeepEditing={() => setDiscarding(false)} />
      <SaveConflict store={store} />
      {saved > 0 && (
        <Alert
          className="studio-save-notice"
          type="success"
          showIcon
          role="status"
          title={<h3>{t('studio.saved', { name })}</h3>}
          action={<Dismiss label={t('studio.dismiss.saved')} onClick={() => setSaved(0)} />}
        />
      )}
    </section>
  );
}

function Dismiss({ label, onClick }: { label: string; onClick: () => void }) {
  return <Button size="small" type="text" aria-label={label} onClick={onClick} icon={<CloseOutlined />} />;
}

function CheckResult({ issues, onDismiss }: { issues: Message[]; onDismiss: () => void }) {
  const texts = useText();
  const { t } = texts;
  const found = issues.length > 0;
  const shown = issues.map((issue) => say(texts, issue));
  const text = found ? [t('studio.check.found'), ...shown].join('\n') : t('studio.check.passed.copy');
  return (
    <Alert
      type={found ? 'warning' : 'success'}
      showIcon
      role="status"
      aria-label={found ? t('studio.check.warning.label') : t('studio.check.success.label')}
      title={<h3>{found ? t('studio.check.found') : t('studio.check.passed')}</h3>}
      description={
        found ? (
          <ul>
            {shown.map((issue, i) => (
              <li key={i}>{issue}</li>
            ))}
          </ul>
        ) : (
          t('studio.check.passed.detail')
        )
      }
      action={
        <Flex>
          <CopyTextButton label={t('studio.check.copy')} text={text} />
          <Dismiss label={t('studio.dismiss.check')} onClick={onDismiss} />
        </Flex>
      }
    />
  );
}

function ChooseMessages({ onChoose }: { onChoose: () => void }) {
  const { t } = useText();
  return (
    <Empty
      description={
        <>
          <h2>{t('studio.chooseMessages')}</h2>
          <p>{t('studio.chooseMessages.why')}</p>
        </>
      }
    >
      <Button onClick={onChoose}>{t('studio.chooseMessages.go')}</Button>
    </Empty>
  );
}
