import React, { Component } from 'react';
import { FormattedMessage } from 'react-intl';
import { withRouter } from 'react-router-dom';
import { inject } from 'mobx-react';
import PropTypes from 'prop-types';
import _ from 'lodash';
import { stores } from '@choerodon/boot';
import { Dropdown, Button, Menu, Icon } from 'choerodon-ui';
import reportList from '../Home/reportList';

const { Item: MenuItem } = Menu;

const ChartSwitch = withRouter(inject('AppState')((props) => {
  const { current, history, location: { search }, AppState } = props;
  const handleClick = (e) => {
    const nextReport = _.find(reportList, ['key', e.key]);
    if (nextReport) {
      history.push(`${nextReport.link}${search}`);
    }
  };
  const menu = (
    <Menu onClick={handleClick}>
      {_.map(_.filter(reportList, (item) => item.key !== current), (rep) => (
        <MenuItem key={rep.key}>
          <FormattedMessage id={`report.${rep.key}.head`} />
        </MenuItem>
      ))}
    </Menu>
  );
  return (
    <Dropdown
      placement="bottomCenter"
      trigger={['click']}
      overlay={menu}
    >
      <Button funcType="flat">
        <FormattedMessage id="chart.change" />
        <Icon type="arrow_drop_down" />
      </Button>
    </Dropdown>
  );
}));

ChartSwitch.propTypes = {
  current: PropTypes.string.isRequired,
};


export default ChartSwitch;
