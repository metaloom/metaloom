import * as React from 'react';
import Title from '../Demo/Title';
import { Link as RouterLink } from 'react-router-dom';
import {Link as MaterialLink} from '@mui/material';


export default function AdminArea() {
    return (
        <React.Fragment>
            <Title>AdminArea</Title>
            <MaterialLink component={RouterLink} to='/'>Dash</MaterialLink>
            <RouterLink to="/">Expenses</RouterLink>
        </React.Fragment>
    )
}