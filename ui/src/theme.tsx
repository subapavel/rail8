import { LoadingOutlined } from '@ant-design/icons';
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

/**
 * The theme of the workspace and of everything Ant Design mounts outside it, with the notification context: compact
 * sizing, English texts, a loading icon that leaves a button at once, and a light or dark mode. The mode starts from
 * the choice saved in localStorage, or else from the system's preference. `children` is called with whether the mode is
 * dark and a function that switches it; a switch is saved in localStorage when the browser allows it.
 */
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
      componentSize="middle"
      theme={{
        algorithm: [dark ? theme.darkAlgorithm : theme.defaultAlgorithm, theme.compactAlgorithm],
        cssVar: { key: 'rail8' },
        token: { fontFamily: 'Inter, "Segoe UI", sans-serif', fontSize: 16, borderRadius: 5 },
        components: { Typography: { titleMarginTop: 0 }, Layout: { headerHeight: 52 } },
      }}
      // A button's own loading icon is shown while it loads and removed as soon as it stops. The default one shrinks
      // away instead, and when that motion does not end, its "loading" stays in the name of a button that is done.
      button={{ loadingIcon: <LoadingOutlined /> }}
    >
      <App className="rail8-app" style={{ colorScheme: mode }}>
        <ModeContext.Provider value={mode}>{children(dark, toggle)}</ModeContext.Provider>
      </App>
    </ConfigProvider>
  );
}
