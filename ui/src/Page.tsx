import { PageContainer, RouteContext, type PageContainerProps } from '@ant-design/pro-components';
import { Breadcrumb } from 'antd';
import { useContext } from 'react';

/**
 * A screen's page in the workspace: Ant Design Pro's PageContainer under the breadcrumb the shell gives each tab, with
 * `title` as the page's heading of level 1. With `title` false the page has no heading of its own; every other prop
 * is PageContainer's: the page's actions as `extra`, its facts as `content` and `extraContent`, its own tabs as
 * `tabList`, and its body as the children.
 */
export function Page({ title, ...rest }: PageContainerProps) {
  return <PageContainer title={title === false ? false : <h1 className="rail8-page-title">{title}</h1>} {...rest} />;
}

/** The breadcrumb the shell gives the tab it is in, for a page that draws its own frame instead of {@link Page}. */
export function TabBreadcrumb() {
  const { breadcrumb } = useContext(RouteContext);
  return breadcrumb?.items?.length ? <Breadcrumb {...breadcrumb} /> : null;
}
