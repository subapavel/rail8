import { ArrowRightOutlined, HomeOutlined } from '@ant-design/icons';
import { Button, Card, Flex, Typography } from 'antd';
import { useContext } from 'react';
import { groupedScreens, RegisteredScreens, type Screen } from './screens';

function Home({ open }: { open: (screen: Screen) => void }) {
  const screens = useContext(RegisteredScreens).filter((entry) => entry.id !== home.id);
  return (
    <section>
      <Typography.Title level={1}>Rail8</Typography.Title>
      <Typography.Paragraph type="secondary">Open a screen to work in its own tab.</Typography.Paragraph>
      {Array.from(groupedScreens(screens), ([group, entries]) => (
        <section key={group} className="rail8-launch-group">
          <Typography.Title level={2}>{group}</Typography.Title>
          <div className="rail8-launch-grid">
            {entries.map((screen) => (
              <Card key={screen.id} size="small">
                <Flex vertical gap="small">
                  <Button
                    type="link"
                    className="rail8-launch"
                    aria-label={screen.title}
                    icon={screen.icon}
                    onClick={() => open(screen)}
                  >
                    {screen.title}
                    <ArrowRightOutlined />
                  </Button>
                  {screen.description && <Typography.Text type="secondary">{screen.description}</Typography.Text>}
                </Flex>
              </Card>
            ))}
          </div>
        </section>
      ))}
    </section>
  );
}

export const home: Screen = {
  id: 'home',
  title: 'Home',
  group: 'Overview',
  icon: <HomeOutlined />,
  render: (open) => <Home open={open} />,
};
