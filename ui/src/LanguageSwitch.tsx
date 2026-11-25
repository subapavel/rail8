import { TranslationOutlined } from '@ant-design/icons';
import { Button, Dropdown } from 'antd';
import { chooseLanguage, LANGUAGE_NAMES, LANGUAGES, useText, type Language } from './language';

/**
 * The header's language switch: a button that lists the UI's languages, each by its name in itself, with the one shown
 * now checked. Choosing one shows the whole UI in it and keeps the choice for the next visit.
 */
export function LanguageSwitch() {
  const { t, language } = useText();
  return (
    <Dropdown
      trigger={['click']}
      menu={{
        selectable: true,
        selectedKeys: [language],
        items: LANGUAGES.map((code) => ({ key: code, label: <span lang={code}>{LANGUAGE_NAMES[code]}</span> })),
        onClick: ({ key }) => chooseLanguage(key as Language),
      }}
    >
      <Button size="small" type="text" icon={<TranslationOutlined />} aria-label={t('frame.language', { name: LANGUAGE_NAMES[language] })}>
        {language.toUpperCase()}
      </Button>
    </Dropdown>
  );
}
