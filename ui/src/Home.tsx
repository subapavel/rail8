import { HomeOutlined } from '@ant-design/icons';
import { Button, Card, Flex, Typography } from 'antd';
import { useContext } from 'react';
import { Page } from './Page';
import { groupedScreens, RegisteredScreens, type Screen } from './screens';

/** The first page: a card for each group of screens, each screen with its name, which opens it, and what it is for. */
function Home({ open }: { open: (screen: Screen) => void }) {
  const screens = useContext(RegisteredScreens).filter((entry) => entry.id !== home.id);
  return (
    <Page title="Home" content="Open a screen to work in its own tab.">
      <Flex vertical gap="large">
        {Array.from(groupedScreens(screens), ([group, entries]) => (
          <Card key={group} title={<h2>{group}</h2>}>
            {entries.map((screen) => (
              <Card.Grid key={screen.id} className="rail8-launch">
                <Flex vertical gap="small" align="flex-start">
                  <Button type="link" className="rail8-link" aria-label={screen.title} icon={screen.icon} onClick={() => open(screen)}>
                    {screen.title}
                  </Button>
                  {screen.description && <Typography.Text type="secondary">{screen.description}</Typography.Text>}
                </Flex>
              </Card.Grid>
            ))}
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
