import { createContext, type ReactNode } from 'react';
import type { ArtifactKind } from './artifacts';
import { labelKey, msg, type Label } from './language';

/**
 * A screen of the application: its id, the title on its tab and in the navigation, the group of the navigation it is
 * listed in, what it is for, and what it shows. The title, the group and the description are labels: a name from data,
 * such as a package's, shown as it is, or a message of the UI's texts, shown in the language the user chose. `render` is
 * given `open`, which opens another screen in a tab of its own, or shows the tab it already has. `unsaved`, when
 * present, is asked when the screen's tab is about to close: null when the screen has no unsaved changes, and the tab
 * closes at once; else what saves them, and the shell asks first whether to save them, close without saving or keep
 * the tab open. Without it the tab closes at once.
 *
 * A screen of the navigation has a plain name as its id, such as `messages`. The tabs a screen opens have ids of
 * the form `<prefix>:<key>`, such as `message:<id>`, so that they never meet the id of a screen of the navigation.
 */
export interface Screen {
  id: string;
  title: Label;
  group?: Label;
  icon?: ReactNode;
  description?: Label;
  /** The action of the admin API the screen needs, such as `message.read`; a caller who may not do it cannot open it. */
  action?: string;
  render: (open: (screen: Screen) => void) => ReactNode;
  unsaved?: () => UnsavedChanges | null;
}

/** What a screen with unsaved changes gives the shell, so that closing its tab can save them first. */
export interface UnsavedChanges {
  /**
   * Saves the changes. Resolves true once they are saved, and false when they are not: the screen then shows why, and
   * its tab stays open.
   */
  save: () => Promise<boolean>;
}

/**
 * What the `module.tsx` of a module of the UI, a folder `src/<name>/`, default-exports: its place in the navigation,
 * after Home and before every module of a higher `order`, its screens, in the order the navigation lists them, and the
 * kinds of artifact it owns, which the tab of a package opens and compares through it.
 */
export interface UiModule {
  order: number;
  screens: readonly Screen[];
  artifacts?: readonly ArtifactKind[];
}

export const RegisteredScreens = createContext<readonly Screen[]>([]);

/** The group of a screen that names none. */
export const WORKSPACE = msg('frame.group.workspace');

/**
 * The screens by their groups, keyed by the group's id, each with its label; groups retain the order in which modules
 * register their screens.
 */
export function groupedScreens(screens: readonly Screen[]): Map<string, { group: Label; entries: Screen[] }> {
  const groups = new Map<string, { group: Label; entries: Screen[] }>();
  for (const screen of screens) {
    const group = screen.group ?? WORKSPACE;
    const found = groups.get(labelKey(group));
    if (found) found.entries.push(screen);
    else groups.set(labelKey(group), { group, entries: [screen] });
  }
  return groups;
}

const PLAIN_NAME = /^[a-z][a-z0-9-]*$/;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

const isLabel = (value: unknown): value is Label => typeof value === 'string' || (isRecord(value) && typeof value.id === 'string');

function isScreen(value: unknown): value is Screen {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    PLAIN_NAME.test(value.id) &&
    isLabel(value.title) &&
    typeof value.render === 'function'
  );
}

function isUiModule(value: unknown): value is UiModule {
  return (
    isRecord(value) &&
    typeof value.order === 'number' &&
    Number.isFinite(value.order) &&
    Array.isArray(value.screens) &&
    value.screens.every(isScreen)
  );
}

/**
 * The screens the navigation lists, in its order: `home`, then the screens of each module, the modules by ascending
 * `order`. `found` holds what each module's file exports, keyed by the file, as `import.meta.glob` with `eager: true`
 * gives it, such as `'./messages/module.tsx'`.
 *
 * Throws, naming the files, when a file does not default-export a `UiModule` whose screens have plain names as ids,
 * when two modules have the same order, and when a screen id is taken twice, Home's included.
 */
export function screenList(home: Screen, found: Readonly<Record<string, unknown>>): readonly Screen[] {
  const modules = Object.keys(found)
    .sort()
    .map((file) => {
      const exported = found[file];
      const module = isRecord(exported) ? exported.default : undefined;
      if (!isUiModule(module)) {
        throw new Error(
          `${file} does not default-export a UiModule: { order: a finite number, screens: [{ id: a plain name, title, render }] }`,
        );
      }
      return { file, module };
    })
    .sort((a, b) => a.module.order - b.module.order);

  modules.forEach(({ file, module }, i) => {
    const before = modules[i - 1];
    if (before !== undefined && before.module.order === module.order) {
      throw new Error(`${before.file} and ${file} have the same order ${module.order}`);
    }
  });

  const owners = new Map([[home.id, 'the shell']]);
  const list = [home];
  for (const { file, module } of modules) {
    for (const screen of module.screens) {
      const owner = owners.get(screen.id);
      if (owner !== undefined) throw new Error(`the screen id "${screen.id}" is taken twice: by ${owner} and by ${file}`);
      owners.set(screen.id, file);
      list.push(screen);
    }
  }
  return list;
}
