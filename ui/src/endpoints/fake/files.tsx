import { Button, Flex } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';
import type { Screen } from '../../screens';

/** A file as a list of the working folder gives it: its path, and its id when it could be read. */
type Listed = { path: string } & ({ id: string } | { problem: string });

/** The buttons of the files `list` gives, each named by its id, else by its file's name, and `extra` after them. */
function Files({ list, open, extra }: { list: () => Promise<{ items: Listed[] }>; open: (path: string, id: string) => void; extra?: ReactNode }) {
  const [items, setItems] = useState<Listed[]>([]);
  useEffect(() => {
    let current = true;
    void list().then(({ items: read }) => current && setItems(read));
    return () => {
      current = false;
    };
  }, [list]);
  return (
    <Flex gap="small" wrap>
      {items.map((item) => {
        const id = 'id' in item ? item.id : item.path.slice(item.path.lastIndexOf('/') + 1);
        return (
          <Button key={item.path} onClick={() => open(item.path, id)}>
            {id}
          </Button>
        );
      })}
      {extra}
    </Flex>
  );
}

/**
 * The screen of the tests of the editors: the files `list` gives as buttons, each opening `tab` of its path and id in a
 * tab of its own, as a package's Artifacts tab opens them, and what `extra` draws with the shell's `open` after them.
 */
export function filesScreen(list: () => Promise<{ items: Listed[] }>, tab: (path: string, id: string) => Screen, extra?: (open: (screen: Screen) => void) => ReactNode): Screen {
  return {
    id: 'files',
    title: 'Files', // untranslated: the title of a screen the tests alone draw
    render: (open) => <Files list={list} open={(path, id) => open(tab(path, id))} extra={extra?.(open)} />,
  };
}
