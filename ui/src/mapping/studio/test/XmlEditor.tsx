import { useEffect, useLayoutEffect, useRef } from 'react';
import { editor } from 'monaco-editor/editor/editor.api';
import 'monaco-editor/languages/definitions/xml/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import { useRail8Mode } from '../../../theme';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

interface XmlEditorProps {
  value: string;
  readOnly: boolean;
  onChange?: (value: string) => void;
  label: string;
}

/** Edits the current test document using the XML language and worker bundled with the application. */
export default function XmlEditor({ value, readOnly, onChange, label }: XmlEditorProps) {
  const mode = useRail8Mode();
  const host = useRef<HTMLDivElement>(null);
  const instance = useRef<editor.IStandaloneCodeEditor>(null);
  const latest = useRef({ value, onChange, readOnly });

  useLayoutEffect(() => {
    latest.current = { value, onChange, readOnly };
  }, [value, onChange, readOnly]);

  useEffect(() => {
    const model = editor.createModel(latest.current.value, 'xml');
    const control = editor.create(host.current!, {
      model,
      readOnly: latest.current.readOnly,
      ariaLabel: label,
      automaticLayout: true,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      wordWrap: 'on',
    });
    instance.current = control;
    const subscription = model.onDidChangeContent(() => {
      const next = model.getValue();
      if (next !== latest.current.value) latest.current.onChange?.(next);
    });
    control.focus();
    return () => {
      subscription.dispose();
      control.dispose();
      model.dispose();
      instance.current = null;
    };
  }, [label]);

  useEffect(() => {
    const control = instance.current;
    if (control && control.getValue() !== value) control.setValue(value);
  }, [value]);

  useEffect(() => {
    instance.current?.updateOptions({ readOnly });
  }, [readOnly]);

  useEffect(() => {
    editor.setTheme(mode === 'dark' ? 'vs-dark' : 'vs');
  }, [mode]);

  return <div ref={host} style={{ height: 'min(65vh, 42rem)' }} />;
}
