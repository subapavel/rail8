import { HomeOutlined, LockOutlined } from '@ant-design/icons';
import { Button, Card, Flex, Typography } from 'antd';
import { useContext } from 'react';
import { GitCredentialsAlert } from './GitCredentialsAlert';
import { Page } from './Page';
import { groupedScreens, RegisteredScreens, type Screen } from './screens';
import { useAccess } from './SessionGate';

/**
 * The first page: the warning while the node's git credentials are refused, then a card for each group of screens, each screen
 * with its name, which opens it, and what it is for. A screen the caller may not open is disabled, and says why.
 */
function Home({ open }: { open: (screen: Screen) => void }) {
  const screens = useContext(RegisteredScreens).filter((entry) => entry.id !== home.id);
  const access = useAccess();
  return (
    <Page title="Home" content="Open a screen to work in its own tab.">
      <Flex vertical gap="large">
        <GitCredentialsAlert />
        {Array.from(groupedScreens(screens), ([group, entries]) => (
          <Card key={group} title={<h2>{group}</h2>}>
            {entries.map((screen) => {
              const may = access(screen.action);
              return (
                <Card.Grid key={screen.id} className="rail8-launch">
                  <Flex vertical gap="small" align="flex-start">
                    <Button type="link" className="rail8-link" aria-label={screen.title} icon={screen.icon} disabled={may !== true} onClick={() => open(screen)}>
                      {screen.title}
                    </Button>
                    {screen.description && <Typography.Text type="secondary">{screen.description}</Typography.Text>}
                    {may !== true && (
                      <Typography.Text type="secondary">
                        <LockOutlined aria-hidden /> {may}
                      </Typography.Text>
                    )}
                  </Flex>
                </Card.Grid>
              );
            })}
          </Card>
        ))}
      </Flex>
    </Page>
  );
}

export const home: Screen = {
  id: 'home',
  title: 'Home',
  group: 'Overview',
  icon: <HomeOutlined />,
  render: (open) => <Home open={open} />,
};
