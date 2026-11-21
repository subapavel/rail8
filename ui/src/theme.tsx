import { App, ConfigProvider, theme } from 'antd';
import { createContext, useContext, useState, type ReactNode } from 'react';
import enUS from 'antd/locale/en_US';
import 'antd/dist/reset.css';
import './workspace.css';

type Mode = 'light' | 'dark';
const ModeContext = createContext<Mode>('light');
export const useRail8Mode = () => useContext(ModeContext);

const preference = 'rail8.theme';

function initialMode(): Mode {
  try {
    const saved = localStorage.getItem(preference);
    if (saved === 'dark' || saved === 'light') return saved;
  } catch {
    /* Storage is optional. */
  }
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** The workspace and Ant portals share the same theme and notification context. */
export function Rail8Theme({ children }: { children: (dark: boolean, toggle: () => void) => ReactNode }) {
  const [mode, setMode] = useState(initialMode);
  const dark = mode === 'dark';
  const toggle = () => {
    const next = dark ? 'light' : 'dark';
    setMode(next);
    try {
      localStorage.setItem(preference, next);
    } catch {
      /* Use the preference for this session. */
    }
  };
  return (
    <ConfigProvider
      locale={enUS}
      componentSize="small"
      theme={{
        algorithm: [dark ? theme.darkAlgorithm : theme.defaultAlgorithm, theme.compactAlgorithm],
        cssVar: { key: 'rail8' },
        token: { fontFamily: 'Inter, "Segoe UI", sans-serif', fontSize: 14, borderRadius: 5 },
        components: { Typography: { titleMarginTop: 0 }, Layout: { headerHeight: 52 } },
      }}
    >
      <App className="rail8-app" style={{ colorScheme: mode }}>
        <ModeContext.Provider value={mode}>{children(dark, toggle)}</ModeContext.Provider>
      </App>
    </ConfigProvider>
  );
}
